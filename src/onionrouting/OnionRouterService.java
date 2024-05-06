package onionrouting;

import merrimackutil.json.JsonIO;
import merrimackutil.json.types.JSONObject;
import onionrouting.onionrouter_cells.*;

import java.util.concurrent.ConcurrentHashMap;
import java.util.Scanner;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.InvalidObjectException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.Socket;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import java.security.KeyPair;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.PrivateKey;
import java.security.Key;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.InvalidKeyException;
import java.security.KeyFactory;
import java.security.spec.X509EncodedKeySpec;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.Security;

import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.util.encoders.Base64Encoder;

/**
 * Class for the threaded service implementation of the OR (to allow for multiple connections through this OR).
 */
public class OnionRouterService implements Runnable {

    private Socket inSock;                                // The incoming socket connection to this OR.
    private Socket outSock;                               // The outgoing socket connection from this OR.
    private ConcurrentHashMap<Integer, Key> keyTable;     // The table used to find symmetric keys based on.
    private ConcurrentHashMap<Integer, Integer> circIDAssociation; // Incoming circIDs -> Outgoing circIDs.
    private ConcurrentHashMap<Integer, String> revTable;  // Incoming circIDs -> Incoming OR IP/port combos.
    private PrivateKey privKey;                           // The private key for this OR.

    /**
     * Constructor for the threaded service implementation of the OnionRouter. This allows for multiple connections.
     * @param keyTable table for retrieving a key based on the circuit ID.
     * @param fwdTable table for forwarding circuit IDs to the next connection based on IP/port combinations.
     * @param inSock socket connection incoming to this OR.
     * @param privKey the private key for this OR.
     */
    public OnionRouterService(ConcurrentHashMap<Integer, Key> keyTable, ConcurrentHashMap<String, Integer> fwdTable, Socket inSock, PrivateKey privKey) {
        this.inSock = inSock;
        this.outSock = null;
        this.keyTable = keyTable;
        this.privKey = privKey;
        // Initialize the BCProvider
        Security.addProvider(new BouncyCastleProvider());
    }

    @Override
    public void run() {
        try {

        BufferedReader reader = new BufferedReader(new InputStreamReader(inSock.getInputStream()));
        BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(inSock.getOutputStream()));

        // Run while the connection is alive in this circuit:
        while(true) {

            // Read the type of the incoming cell
            JSONObject obj = JsonIO.readObject(reader.readLine());
            if(!obj.containsKey("type"))
            {
                System.err.println("Could not determine the type of the cell. Cell will be dropped");
                continue;
            }

            String type = obj.getString("type");

            try {
                switch(type) {
                    case "RELAY":
                        RelayCell relayCell = new RelayCell(obj);

                        doRelay(relayCell);
                        break;
                    case "CREATE":
                        CreateCell createCell = new CreateCell(obj);

                        try {
                            doCreate(createCell, writer);
                        } catch(Exception e) {
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
                    case "EXTEND":
                        ExtendCell extendCell = new ExtendCell(obj);

                        doExtend(extendCell);
                        break;
                    case "EXTENDED":
                        ExtendedCell extendedCell = new ExtendedCell(obj);

                        doExtended(extendedCell);
                        break;
                }
            } catch (InvalidObjectException ex) {
                System.err.println("Invalid Object parsed.");
                System.err.println(ex.getMessage());
            }
        }
        } catch(IOException e) {
            e.printStackTrace();
    }
    }


    /**
     * Performs all the operations to be done on a Relay cell when received.
     * 
     * Steps:
     *  1. Decrypt
     *  2. Pass it along
     * 
     * @param cell cell we're performing the operation on.
     */
    private void doRelay(RelayCell cell) {
        // TODO: RELAY

        /*
         * Notes: Relay basically means "encrypted" for our implementation,
         *        and thus do not interpret -- just relay the message along.
         */
    }



    /**
     * Performs all the operations to be done on a Create cell when received.
     * 
     * Steps:
     *  1. Get gX from the cell.
     *  2. Get the shared secret + create K
     *  3. Send back a CreatedCell(gY, H(K || "handshake"))
     *      a. Note: No encryption on this part. None needed b/c gY is not enough to make K vulnerable
     *  4. Store circID + K in keyTable
     * 
     * @param cell cell we're performing the operation on.
     * @throws IOException 
     */
    private void doCreate(CreateCell cell, BufferedWriter output) throws NoSuchAlgorithmException,
            InvalidKeyException, InvalidKeySpecException, IOException{
        // 1. Get gX from the cell. Then convert it to a Public Key for DH magic.
        // Decrypt gX so it can be used.
        byte[] gX = decryptHybrid(cell.getEncryptedSymKey(), cell.getgX());

        // If gX is null, that means we encountered an error. Send back a CreatedCell with all empty fields and return.
        if(gX == null) {
            CreatedCell retCell = new CreatedCell("", "");
            output.write(retCell.serialize());
            output.newLine();
            output.flush();
            return;
        }

        // Load the public value from the other side.
        X509EncodedKeySpec spec = new X509EncodedKeySpec(gX);
        PublicKey gXPubKey = KeyFactory.getInstance("EC").generatePublic(spec);


        // 2. Diffie-Hellman stuff
        KeyAgreement ecdhKex = KeyAgreement.getInstance("ECDH"); // Eliptic Curve Diffie-Hellman
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC"); // Generator for elliptic curves (this is our group)    
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
        
        // 4. Store circID + key K in table
        keyTable.put(cell.getCircID(), new SecretKeySpec(sharedSecret, "AES"));

        // Package in CreatedCell and return it back.
        CreatedCell retCell = new CreatedCell(gY, kHash);
        output.write(retCell.serialize());
        output.newLine();
        output.flush();
    }



    /**
     * Performs all the operations to be done on a Created cell when received.
     * @param cell cell we're performing the operation on.
     */
    private void doCreated(CreatedCell cell) {
        // TODO: CREATED
        
        /*
         * Steps:
         *  1. Encapsulate in an ExtendedCell
         *  2. Send back the ExtendedCell
         * 
         *  Notes: CREATE and CREATED are sent + received by the last OR before the extension occurs.
         *         Additionally, sending BACK a cell is simple. inSockOut.println(ExtendedCell);
         */
    }



    /**
     * Performs all the operations to be done on a Destroy cell when received.
     * @param cell cell we're performing the operation on.
     */
    private void doDestroy(DestroyCell cell) {
        // TODO: DESTROY
        
        /*
         * Steps:
         *  1. Break down connections to inSock
         *  2. Send DestroyCell() forward to next OR (if one exists, check fwd table)
         *  3. Break down connections to outSock
         * 
         *  Notes: Might need to bring the Scanners/PrintWriters and sockets
         *         themselves as args to this method.
         */
    }

    /**
     * Performs all the operations to be done on an Extend cell when received.
     * 
     * Steps:
     *  1. Come up with a circID for the next node.
     *  2. Send Create(newCirdID) to specified IP/port combo
     * 
     * @param cell cell we're performing the operation on.
     */
    private void doExtend(ExtendCell cell) {
        // 1. Come up with a new circID
        int newCircID = (int)(Math.random() * Integer.MAX_VALUE);
    }



    /**
     * Performs all the operations to be done on an Extended cell when received.
     * @param cell cell we're performing the operation on.
     */
    private void doExtended(ExtendedCell cell) {
        // TODO: EXTENDED
        
        /*
         * Steps:
         *  1. Encrypt in sym key for Alice
         *  2. Send Relay() message back towards Alice
         */
    }



    /*
        Helper methods
    */

    /**
     * Used to decrypt for G^x (ciphertext) 
     * and decrypt the symmetric key (encrypted_sym_key) using PrivateKey in this OnionRouter.
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
            cipher.init(Cipher.DECRYPT_MODE, privKey);
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
    
}
