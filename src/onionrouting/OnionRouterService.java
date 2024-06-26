package onionrouting;

import merrimackutil.json.JsonIO;
import merrimackutil.json.types.JSONObject;
import onionrouting.onionrouter_cells.*;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.InvalidObjectException;
import java.io.OutputStreamWriter;
import java.net.Socket;
import java.util.Base64;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.KeyAgreement;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import java.security.KeyPair;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.Key;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.KeyFactory;
import java.security.spec.X509EncodedKeySpec;
import java.security.spec.InvalidKeySpecException;
import java.security.Security;
import org.bouncycastle.jce.provider.BouncyCastleProvider;

/**
 * Class for the threaded service implementation of the OR (to allow for
 * multiple connections through this OR).
 */
public class OnionRouterService implements Runnable {

    private Socket inSock; // The incoming socket connection to this OR.

    /**
     * Constructor for the threaded service implementation of the OnionRouter. This
     * allows for multiple connections.
     * 
     * @param inSock   socket connection incoming to this OR.
     */
    public OnionRouterService(Socket inSock) {
        this.inSock = inSock;

        // Initialize the BCProvider
        Security.addProvider(new BouncyCastleProvider());
    }

    @Override
    public void run() {
        try {
            BufferedReader reader = new BufferedReader(new InputStreamReader(inSock.getInputStream()));

            // Run while the connection is alive in this circuit:

            // Read the type of the incoming cell
            String msg = reader.readLine();
            JSONObject obj = JsonIO.readObject(msg);
            if (!obj.containsKey("type")) {
                System.err.println("Could not determine the type of the cell. Cell will be dropped");
                return;
            }

            String type = obj.getString("type");

            if(OnionRouter.getConf().isVerbose()) {
                System.out.println("["+type+" Cell Received] with host: " + inSock.getInetAddress().getHostAddress() +":"+inSock.getPort());
            }

            inSock.close();

            try {
                switch (type) {
                    case "RELAY":
                        RelayCell relayCell = new RelayCell(obj);

                        doRelay(relayCell);
                        break;
                    case "CREATE":
                        CreateCell createCell = new CreateCell(obj);

                        try {
                            doCreate(createCell);
                        } catch (Exception e) {
                            System.err.println("Could not complete DH KEX with Alice properly.");
                            System.err.println(e);
                        }
                        break;
                    case "CREATED":
                        CreatedCell createdCell = new CreatedCell(obj);

                        doCreated(createdCell);
                        break;
                    case "DESTROY":
                        DestroyCell destroyCell = new DestroyCell(obj);

                        doDestroy(destroyCell);
                        break;
                    case "DATA":
                        DataCell dataCell = new DataCell(obj);

                        // 1. Send it to the server. No CircID needed (THIS IS TEST CODE)
                        sendToServer(dataCell.getChild().toJSON(), dataCell.getServerAddr(), dataCell.getServerPort(), "");

                        break;
                    default:
                        System.err.println("Unknown cell type. Closing socket...");

                        break;
                }
            } catch (InvalidObjectException ex) {
                System.err.println("Invalid Object parsed.");
                System.err.println(ex.getMessage());
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * Performs all the operations to be done on a Relay cell when received.
     * 
     * Steps:
     * 1. Decrypt
     * 2. Pass it along
     * 
     * @param cell cell we're performing the operation on.
     */
    private void doRelay(RelayCell cell) {
        // 1. Check if it's incoming or outgoing. This is done by checking if it's in the inTable or outTable
        String circID = cell.getCircID();
        
        // a. If it's incoming from Alice (i.e. the circID is in the inTable).
        if (OnionRouter.getInTable().containsKey(circID)) {
            // 1. Update the iv table with the iv we received. This will be used on the way back to Alice to encrypt.
            OnionRouter.getIVTable().put(circID, cell.getIV());

            // 2. Decrypt the Relay cell's secret (contains destination IP/port + child).
            // Get the key
            Key key = OnionRouter.getKeyTable().get(cell.getCircID());
            RelaySecret secret = null;
            try {
                // Decrypt and get the RelaySecret
                String result = decryptCBC(cell.getRelaySecret(), key, cell.getIV());
                secret = new RelaySecret(JsonIO.readObject(result));
            } catch (InvalidObjectException e) {
                System.err.println("Error. Incorrect format for RelaySecret JSON: ");
                System.err.println(e);
            } catch (Exception e) {
                System.err.println("Error. RelaySecret was unable to be decrypted properly: ");
                System.err.println(e);
            }

            // 3. Send the child to its destination
            String addr = secret.getAddr();
            int port = secret.getPort();
            JSONObject child = secret.getChild();

            // a. If we're sending a CreateCell, we save the information of the OR to the outTable
            if(child.containsKey("type")) {
                if(child.getString("type").equals("CREATE")) {
                    String outCircID = child.getString("circID"); // Get the circID Alice assigned for the next OR in the circuit
                    // Put the:
                    //  i. outCircID -> outgoing OR info in the outTable
                    // ii. outCircID -> inCircID in the askTable (for when we do the reverse direction).
                    OnionRouter.getOutTable().put(outCircID, addr + ":" + port);
                    OnionRouter.getAskTable().put(outCircID, circID);

                    // Also need to overwrite the srcAddr + srcPort fields
                    child.put("srcAddr", OnionRouter.getAddr());
                    child.put("srcPort", OnionRouter.getPort());

                }
                else if(child.getString("type").equals("DATA")) {
                    try {
                        DataCell dataCell = new DataCell(child);
                        addr = dataCell.getServerAddr();
                        port = dataCell.getServerPort();

                        sendToServer(dataCell.getChild().toJSON(), addr, port, circID);
                        return;
                    } catch (InvalidObjectException e) {
                        // TODO Auto-generated catch block
                        e.printStackTrace();
                    }
                }
                    
            }

            // b. Actually send to the socket.
            sendToDestination(child.toJSON(), addr, port);
        }
        // b. If it's returning TO Alice (i.e. the circID is in the outTable).
        else if (OnionRouter.getOutTable().containsKey(circID)) {
            // 1. Package it in a RelayCell
            RelayCell retCell = packageInRelayCell(cell);

            // 2. Get the addr/port of the previous node from the inTable
            String addrPortCombo = OnionRouter.getInTable().get(retCell.getCircID());
            String[] segments = addrPortCombo.split(":");
            String addr = segments[0];
            int port = Integer.parseInt(segments[1]);

            // 3. Send it off!
            sendToDestination(retCell.serialize(), addr, port);
        }

        // c. Else, drop it
    }

    /**
     * Performs all the operations to be done on a Create cell when received.
     * 
     * Steps:
     * 1. Get gX from the cell.
     * 2. Get the shared secret + create K
     * 3. Send back a CreatedCell(gY, H(K || "handshake"))
     * a. Note: No encryption on this part. None needed b/c gY is not enough to make
     * K vulnerable
     * 4. Store circID + K in keyTable
     * 
     * @param cell cell we're performing the operation on.
     * @throws IOException
     */
    private void doCreate(CreateCell cell) throws NoSuchAlgorithmException,
            InvalidKeyException, InvalidKeySpecException, IOException {
        
        // 1. Get gX from the cell. Then convert it to a Public Key for DH magic.
        // Decrypt gX so it can be used.
        byte[] gX = decryptHybrid(cell.getEncryptedSymKey(), cell.getgX());

        // If gX is null, that means we encountered an error. Send back a CreatedCell
        // with all empty fields and return.
        if (gX == null) {
            CreatedCell retCell = new CreatedCell("", "", "");
            sendToDestination(retCell.serialize(), cell.getSrcAddr(), cell.getSrcPort());
            return;
        }

        // Load the public value from the other side.
        X509EncodedKeySpec spec = new X509EncodedKeySpec(gX);
        PublicKey gXPubKey = KeyFactory.getInstance("EC").generatePublic(spec);

        // 2. Diffie-Hellman stuff
        KeyAgreement ecdhKex = KeyAgreement.getInstance("ECDH"); // Eliptic Curve Diffie-Hellman
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC"); // Generator for elliptic curves (this is our
                                                                         // group)
        generator.initialize(256);

        // Generate the OR's contribution of the symmetric key.
        KeyPair pair = generator.generateKeyPair();
        String gY = Base64.getEncoder().encodeToString(pair.getPublic().getEncoded());

        // Generate the shared secret
        ecdhKex.init(pair.getPrivate());
        ecdhKex.doPhase(gXPubKey, true);
        byte[] sharedSecret = ecdhKex.generateSecret();

        // 3. Send back CreatedCell(gY, H(K || "handshake"))

        // Get the hash
        MessageDigest md = MessageDigest.getInstance("SHA3-256");
        md.update(sharedSecret);
        md.update("handshake".getBytes());
        String kHash = Base64.getEncoder().encodeToString(md.digest());

        // 4. Store circID + key K in table. Also store incoming connection + circID in inTable
        OnionRouter.getKeyTable().put(cell.getCircID(), new SecretKeySpec(sharedSecret, "AES"));
        OnionRouter.getInTable().put(cell.getCircID(), cell.getSrcAddr() + ":" + cell.getSrcPort());

        // Package in CreatedCell and return it back.
        CreatedCell retCell = new CreatedCell(gY, kHash, cell.getCircID());
        sendToDestination(retCell.serialize(), cell.getSrcAddr(), cell.getSrcPort());
    }

    /**
     * Performs all the operations to be done on a Created cell when received.
     * 
     * Steps:
     * 1. Encapsulate in a RelayCell
     * 2. Send back the RelayCell
     * 
     * @param cell cell we're performing the operation on.
     */
    private void doCreated(CreatedCell cell) {
         // 1. Package it in a RelayCell
         RelayCell retCell = packageInRelayCell(cell);

         // 2. Get the addr/port of the previous node from the inTable
         String addrPortCombo = OnionRouter.getInTable().get(retCell.getCircID());
         String[] segments = addrPortCombo.split(":");
         String addr = segments[0];
         int port = Integer.parseInt(segments[1]);

         // 3. Send it off!
         sendToDestination(retCell.serialize(), addr, port);
    }

    /**
     * Performs all the operations to be done on a Destroy cell when received.
     * 
     * @param cell cell we're performing the operation on.
     */
    private void doDestroy(DestroyCell cell) {
        // TODO: DESTROY

        /*
         * Steps:
         * 1. Break down connections to inSock
         * 2. Send DestroyCell() forward to next OR (if one exists, check fwd table)
         * 3. Break down connections to outSock
         * 
         * Notes: Might need to bring the Scanners/PrintWriters and sockets
         * themselves as args to this method.
         */

         if(cell.getCircID() == null) {
            System.err.print("Could not find this.circID from the in Destroy cell.");
            return;
        }

        Set<String> outCircIdsToRemove = new HashSet<>();

        //
        // Value search of the Ask table
        //     For each of the KVP find the Key in the out table and then send a new destroy cell to the out OR
        for(Map.Entry<String,String> entry : OnionRouter.getAskTable().entrySet()) {
        String outId = entry.getKey();
        String inId = entry.getValue();

            // We know that this outId associaton has 
            if(inId.equalsIgnoreCase(cell.getCircID())) {
                // Append the outId to outCircIdsToRemove so it can get removed from this OR
                outCircIdsToRemove.add(outId);
                
                // Get the address and port using the circID
                String[] segments = OnionRouter.getOutTable().get(outId).split(":");
                String destroyCellAddr = segments[0];
                int destroyCellPort = Integer.parseInt(segments[1]);

                // Send the destroy cell to the next OR
                DestroyCell destroyCell = new DestroyCell(outId);
                sendToDestination(destroyCell.serialize(), destroyCellAddr, destroyCellPort);
            }
        }

        // Remove all fields keyTable, ivTable, askTable, inTable, outTable;
        OnionRouter.getKeyTable().remove(cell.getCircID());
        OnionRouter.getIVTable().remove(cell.getCircID());
        OnionRouter.getAskTable().remove(cell.getCircID());
        OnionRouter.getInTable().remove(cell.getCircID());
        outCircIdsToRemove.forEach(n -> OnionRouter.getOutTable().remove(n));
    }

    /*
     * Helper methods
     */

     /**
      * Packages some abstract cell into a RelayCell. 
      *
      * @param cell some cell we want to package.
      * @return RelayCell encapsulating the input cell, or null if a failure occurred.
      */
    public RelayCell packageInRelayCell(Cell cell) {
        // 1. Get this.circID from the outgoing circID ("outgoing" in this context means we're receiving a returning RelayCell)
        String thisCircID = OnionRouter.getAskTable().get(cell.getCircID());
        if(thisCircID == null) {
            System.err.print("Could not find this.circID from the askTable.");
            return null;
        }

        // 2. Use this.circID to get the iv + key. We will use these to encrypt
        String iv = OnionRouter.getIVTable().get(thisCircID);
        byte[] rawIV = Base64.getDecoder().decode(iv);
        Key key = OnionRouter.getKeyTable().get(thisCircID);

        // 3. Encrypt the RelayCell and package it into a RelaySecret (will be wrapped in another RelayCell).
        RelaySecret secret = new RelaySecret("", 0, (JSONObject) cell.toJSONType());
        String ctextSecret = null;
        try {
            ctextSecret = encryptSymmetric(secret.serialize(), key, rawIV);
        } catch (Exception e) {
            System.err.println("Unable to encrypt returning RelayCell message");
            return null;
        }
        
        // 4. Return the RelayCell
        return new RelayCell(thisCircID, iv, ctextSecret);
    }

    /**
     * Overloaded method of packaging something into a RelayCell by providing an abstract JSONObject and the circID of this OR.
     * 
     * @param obj object to encapsulate.
     * @param circID circID of this OR
     * @return RelayCell encapsulation or null if an error occurred.
     */
    public RelayCell packageInRelayCell(JSONObject obj, String circID) {
        // 1. Use this.circID to get the iv + key. We will use these to encrypt
        String iv = OnionRouter.getIVTable().get(circID);
        byte[] rawIV = Base64.getDecoder().decode(iv);
        Key key = OnionRouter.getKeyTable().get(circID);

        // 2. Encrypt the RelayCell and package it into a RelaySecret (will be wrapped in another RelayCell).
        // Empty secret is Alright since we are not returning anything
        RelaySecret secret = new RelaySecret("", 0, obj);
        String ctextSecret = null;
        try {
            ctextSecret = encryptSymmetric(secret.serialize(), key, rawIV);
        } catch (Exception e) {
            System.err.println("Unable to encrypt returning RelayCell message");
            return null;
        }
        
        // 3. Return the RelayCell
        return new RelayCell(circID, iv, ctextSecret);
    }

     /**
     * Function to encrypt a message given an AES key and 16-bit salt.
     * @param message String plaintext-message to be encrypted
     * @param aesKey AES Key that will be used to encrypt the message
     * @param rawIV byte[] 16-bit IV that is used to make the encryption non-deterministic
     * @return Result of the encryption as a String.

     */
    public static String encryptSymmetric(String message, Key aesKey, byte[] rawIV) throws IllegalBlockSizeException, BadPaddingException, InvalidKeyException, InvalidAlgorithmParameterException, NoSuchAlgorithmException, NoSuchPaddingException {
        // Set up an AES cipher object.
        Cipher aesCipher = Cipher.getInstance("AES/CBC/PKCS5Padding");

        // Fill array with random bytes.
        IvParameterSpec iv = new IvParameterSpec(rawIV);
                                          
        // Put the cipher in encrypt mode with the generated key 
        aesCipher.init(Cipher.ENCRYPT_MODE, aesKey, iv);

        // Encrypt the entire message at once. The doFinal method 
        byte[] ciphertext = aesCipher.doFinal(message.getBytes());

        return Base64.getEncoder().encodeToString(ciphertext);
    }

    /**
     * Decrypts the ciphertext using AES-CBC 256.
     * 
     * @param ctextStr Base64-encoded ciphertext.
     * @param key      key we use to decrypt.
     * @param iv       IV used to encrypt this ctext.
     * @return String representation of the plaintext.
     */
    public String decryptCBC(String ctextStr, Key key, String ivStr)
            throws NoSuchAlgorithmException, NoSuchPaddingException,
            InvalidKeyException, InvalidAlgorithmParameterException, IllegalBlockSizeException, BadPaddingException {
        // Set up an AES cipher object.
        Cipher aesCipher = Cipher.getInstance("AES/CBC/PKCS5Padding");

        // Fill array with random bytes.
        IvParameterSpec iv = new IvParameterSpec(Base64.getDecoder().decode(ivStr));

        // Put the cipher in encrypt mode with the generated key
        aesCipher.init(Cipher.DECRYPT_MODE, key, iv);

        // Encrypt the entire message at once. The doFinal method
        byte[] plaintext = aesCipher.doFinal(Base64.getDecoder().decode(ctextStr));

        return new String(plaintext);
    }

    /**
     * Used to decrypt for G^x (ciphertext)
     * and decrypt the symmetric key (encrypted_sym_key) using PrivateKey in this
     * OnionRouter.
     * 
     * @param encrypted_sym_key
     * @param cyphertext
     * @return
     */
    public byte[] decryptHybrid(final String encrypted_sym_key, final String cyphertext) {
        try {
            // Decrypt the Symmetric Key
            // Initialize the cipher + decrypt
            Cipher cipher = Cipher.getInstance("ElGamal/None/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, OnionRouter.getPrivKey());
            byte[] sym_key = cipher.doFinal(Base64.getDecoder().decode(encrypted_sym_key));
            String[] sym_key_iv_split = new String(sym_key).split(":");

            // Split the Key:IV string up by colon (:)
            final String key = sym_key_iv_split[0];
            final String iv = sym_key_iv_split[1];

            // Set up an AES cipher object.
            Cipher aesCipher = Cipher.getInstance("AES/CBC/PKCS5Padding");

            SecretKeySpec aesKey = new SecretKeySpec(Base64.getDecoder().decode(key), "AES");

            // Put the cipher in decrypt mode with the specified key.
            aesCipher.init(Cipher.DECRYPT_MODE, aesKey, new IvParameterSpec(Base64.getDecoder().decode(iv)));

            // Decrypt the message all in one call.
            byte[] plaintext = aesCipher.doFinal(Base64.getDecoder().decode(cyphertext));

            return plaintext;
        } catch (Exception e) {
            System.err.println("Error decrypting gX from CreateCell.");
            System.err.println(e);
            return null;
        }
    }


    /**
     * Sends a message to a particular IP/port combo bound on this OR's port.
     * 
     * @param msg Message to send.
     * @param addr Address to send to.
     * @param port Port to send to.
     */
    private void sendToDestination(String msg, String addr, int port) {
        try {
            // Create a socket and bind it to the specified port
            Socket socket = new Socket(addr, port);

            // Send it out
            BufferedWriter output = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream()));
            output.write(msg);
            output.newLine();
            output.close();

            if(OnionRouter.getConf().isVerbose()) {
                System.out.println("[Sent to Server] to host: " + addr +":"+port);
            }
            
            // Close the socket when done
            socket.close();
        } catch (IOException e) {
            System.err.println("Could not send message to: [" + addr + ":" + port + "].");
            e.printStackTrace();
        }
    }

    /**
     * Sends a message to a particular server (based on IP/port combo) and expects a result.
     * 
     * @param msg Message to send.
     * @param addr Address to send to.
     * @param port Port to send to.
     */
    private void sendToServer(String msg, String addr, int port, String circID) {
        try {
            // Create a socket and bind it to the specified port
            Socket socket = new Socket(addr, port);

            // Send it out
            BufferedReader input = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            BufferedWriter output = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream()));
            output.write(msg);
            output.newLine();
            output.flush();

            if(OnionRouter.getConf().isVerbose()) {
                System.out.println("[Cell Sent] to host: " + addr +":"+port);
            }

            // Wait for the response
            String res = input.readLine();

            // Package it in a RelayCell and send it off!
            RelayCell cell = packageInRelayCell(JsonIO.readObject(res), circID);

            // Get the address and port using the circID
            String[] segments = OnionRouter.getInTable().get(circID).split(":");
            String retAddr = segments[0];
            int retPort = Integer.parseInt(segments[1]);
            sendToDestination(cell.serialize(), retAddr, retPort);
            
            // Close the socket when done
            socket.close();
        } catch (IOException e) {
            System.err.println("Could not send message to: [" + addr + ":" + port + "].");
            e.printStackTrace();
        }
    }

}
