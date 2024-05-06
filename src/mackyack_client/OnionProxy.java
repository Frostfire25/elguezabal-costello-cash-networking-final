package mackyack_client;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.UnknownHostException;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.Security;
import java.security.spec.InvalidKeySpecException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.KeyAgreement;
import javax.crypto.NoSuchPaddingException;

import org.bouncycastle.jce.provider.BouncyCastleProvider;

import merrimackutil.json.JSONSerializable;
import merrimackutil.json.JsonIO;
import merrimackutil.json.types.JSONObject;
import merrimackutil.util.Pair;
import onionrouting.OnionRouterService;
import onionrouting.onionrouter_cells.CreateCell;
import onionrouting.onionrouter_cells.RelayCell;

public class OnionProxy {

    private final Random rand = new Random();

    private final static int ROUTER_COUNT = 3;
    private RoutersConfig routersConfig;
    private ClientConfig conf;

    private List<Router> circuit = new ArrayList<>();

    // Represents the circuitID for entering the entry node
    private int circID;

    public Router getEntryRouter() {
        return circuit.get(0);
    }

    /**
     * Default Constructor to initialize the Onion Routing System.
     * @throws Exception 
     */
    public OnionProxy(RoutersConfig routersConfig, ClientConfig conf) throws Exception {
        this.routersConfig = routersConfig;
        this.conf = conf;
        this.circID = rand.nextInt();

        // Initialize the BCProvider
        Security.addProvider(new BouncyCastleProvider());

        // build the circuit
        constructCircuit();

        // Construct create cells for each OR
        List<CreateCell> createCells = constructCreateCells();

        // Construct a list of messages (Relays) to initiate the circuit keys
        List<JSONSerializable> create_and_relay_messages = createRelays(createCells);

        System.out.println(Arrays.toString(circuit.toArray()));

        for(JSONSerializable n : create_and_relay_messages) {
            System.out.println(n.toJSONType().getFormattedJSON());
        }

        // Poll for new messages on the proxy
        pollProxy();
    }

    /**
     * Abstract Function to send a Message.
     * Message construction does not happen at this level.
     * This function strictly sends a Message to the entrance OR.
     * Once the entrance OR receives, the socket will be closed on the OR's end.
     * @param message
     * @throws UnknownHostException
     * @throws IOException
     */
    public void send(JSONSerializable message) throws UnknownHostException, IOException {
        // We can only send to the entrance node in a OR scheme.
        // So that's what we'll do
        Router en_Router = getEntryRouter();

        Socket sock = new Socket(en_Router.getAddr(), en_Router.getPort());
        BufferedReader reader = new BufferedReader(new InputStreamReader(sock.getInputStream()));
        BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(sock.getOutputStream()));
        writer.write(message.serialize());
        writer.newLine();
        writer.flush();
    }

    private void pollProxy() {
        // Start the server socket in a separate thread
        Thread serverThread = new Thread(() -> {
            while(true) {
                try {
                    ServerSocket serverSocket = new ServerSocket(conf.getPort());
                    Socket sock = serverSocket.accept();
                    BufferedReader reader = new BufferedReader(new InputStreamReader(sock.getInputStream()));
                    BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(sock.getOutputStream()));

                    // Determine if the packet is handled at the Proxy Layer or at the ApplicationService Layer
                    JSONObject obj = JsonIO.readObject(reader.readLine());

                    if(obj.containsKey("type")) {
                        // TODO
                        // If this is a CreatedCell, then handle.
                        // ?
                    } else {
                        ApplicationService.handle(obj);
                    }


                    // Protocol is to close the socket after a message has been handled.
                    sock.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        });
        serverThread.start(); // Start the server thread
    }

    /**
     * Construct all of the relay messages from each cell
     * Each CreateCell is associated with the respective Router from {@code circuit} 
     * 
     * @param createCells List<CreateCell> 
     * @return List<JSONSerializable> - A list containing either RelayCells or CreateCells that will be used to send a message. Each node in the list will be sent to the entry node. 
     */
    private List<JSONSerializable> createRelays(List<CreateCell> createCells) {
        List<JSONSerializable> relayCells = new ArrayList<>();

        // Start at index 1 since the entry node does not receive a relay cell
        relayCells.add(createCells.get(0));
        for(int i = 1; i < createCells.size(); i++) {

            RelayCell relayCell = new RelayCell(circID, circuit.get(i).getAddr(), circuit.get(i).getPort(), (JSONObject) createCells.get(i).toJSONType());

            if( i > 1 ) {
                // Loop through all of the Routers from 0 -> (i-2), and append them to the RelayCell
                // This should only be ran if there are 2+ Relays to be made
                for(int j = i - 1; j > 0; j--) {
                    // Create a new RelayCell wrapping 
                    RelayCell newRelayCell = new RelayCell(circID, circuit.get(j).getAddr(), circuit.get(j).getPort(), (JSONObject) relayCell.toJSONType());
                    relayCell = newRelayCell;
                }
            }
            
            // Append to the array
            relayCells.add(relayCell);
        }

        return relayCells;
    }

    private List<CreateCell> constructCreateCells() throws InvalidKeyException, NoSuchAlgorithmException, InvalidKeySpecException, NoSuchPaddingException, IllegalBlockSizeException, BadPaddingException, InvalidAlgorithmParameterException {
        List<CreateCell> ret = new ArrayList<>();

        for(Router n : circuit) {
            // 1. Generate the first half of the DH KEX.
            KeyAgreement ecdhKex = KeyAgreement.getInstance("ECDH"); // Eliptic Curve Diffie-Hellman
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC"); // Generator for elliptic curves (this is our group)    
            generator.initialize(256);

            // Generate the OR's contribution of the symmetric key.
            KeyPair pair = generator.generateKeyPair();
            byte[] gXBytes = pair.getPublic().getEncoded();
            
            // Initialize the Cipher for encryption
            //Cipher cipher = Cipher.getInstance("ElGamal/None/NoPadding");
            //cipher.init(Cipher.ENCRYPT_MODE, OnionProxyUtil.getPublicKey("ElGamal", n.getPublicKey()));

            // Pair of <SymmetricKey:IV> & <Cipher text of Symmetric Encrypted g^x as bytes.
            Pair<String> symmetricKey_CipherText = OnionProxyUtil.encryptHybrid(gXBytes);

            // Encrypt the symmetricKey_CipherText Key+IV
            //byte[] encrypted_sym_key = cipher.doFinal(symmetricKey_CipherText.getFirst().getBytes());

            // B64_Encrypted SYM Key
            //String B64_encrypted_sym_key = Base64.getEncoder().encodeToString(encrypted_sym_key);
            String B64_encrypted_sym_key = "";

            // 2. Send a CreateCell 
            CreateCell cell = new CreateCell(symmetricKey_CipherText.getSecond(), circID, B64_encrypted_sym_key);
            ret.add(cell);
        }

        return ret;
    } 

    /**
     * Constructs the circuit from the routers config.
     * @throws Exception 
     */
    private void constructCircuit() throws Exception {

        if(routersConfig.getRouters() == null || routersConfig.getRouters().size() < ROUTER_COUNT)
            throw new Exception("Invalid count of unique routers in routers.json");

        // Create a copy of getRouters
        List<Router> copy = new ArrayList<Router>();
        copy.addAll(routersConfig.getRouters());
        //Shuffle
        Collections.shuffle(copy);
        // Select the top {ROUTER_COUNT} Routers as OR.
        
        for(int i = 0; i < ROUTER_COUNT; i++)
            circuit.add(copy.get(i));
    }




}
