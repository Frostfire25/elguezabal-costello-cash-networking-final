package mackyack_server;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InvalidObjectException;
import java.security.NoSuchAlgorithmException;
import merrimackutil.cli.LongOption;
import merrimackutil.cli.OptionParser;
import merrimackutil.json.JsonIO;
import merrimackutil.util.Pair;
import merrimackutil.util.Tuple;


/**
 * This is the Mack Yack Server. Its job is to service Mack Yack to any anonymous parties that want to use it.
 */
public class MackYackServer
{
    public static boolean doHelp = false;               // True if help option present.
    private static ServerConfig conf = null;            // The configuration information.
    private static Messages messages;                   // Data for reading / writing messages
    private static String configFile = "./configs/server-config.json";   // Default configuration file.

    private static ServerService serverService;         // Service for managing the servers receiving information
    
    /**
     * Prints the usage to the screen and exits.
     */
    public static void usage() {
        System.out.println("usage:");
        System.out.println("  mackyack_server --config <config>");
        System.out.println("  mackyack_server --help");
        System.out.println("options:");
        System.out.println("  -c, --config\t\tConfig file to use.");
        System.out.println("  -h, --help\t\tDisplay the help.");
        System.exit(1);
    }

    /**
     * Processes the command line arugments.
     * @param args the command line arguments.
     */
    public static void processArgs(String[] args)
    {
        OptionParser parser;
        boolean doHelp = false;
        boolean doConfig = false;

        LongOption[] opts = new LongOption[2];
        opts[0] = new LongOption("help", false, 'h');
        opts[1] = new LongOption("config", true, 'c');
        
        Tuple<Character, String> currOpt;

        parser = new OptionParser(args);
        parser.setLongOpts(opts);
        parser.setOptString("hc:");


        while (parser.getOptIdx() != args.length)
        {
            currOpt = parser.getLongOpt(false);

            switch (currOpt.getFirst())
            {
                case 'h':
                    doHelp = true;
                break;
                case 'c':
                    doConfig = true;
                    configFile = currOpt.getSecond();
                break;
                case '?':
                    System.out.println("Unknown option: " + currOpt.getSecond());
                    usage();
                break;
            }
        }

        // Verify that that this options are not conflicting.
        if ((doConfig && doHelp))
            usage();
        
        if (doHelp)
            usage();
        
        try 
        {
            loadConfig();
        } 
        catch (FileNotFoundException e) 
        {
            System.exit(1);
        }
    }

    /**
     * Loads the configuration file.
     * @throws FileNotFoundException if the configuration file could not be found.
     */
    public static void loadConfig() throws FileNotFoundException
    {
        try
        { 
            conf = new ServerConfig(configFile);
            messages = new Messages(conf.getMessagesPath());
        }
        catch(InvalidObjectException ex)
        {
            System.err.println("Invalid configuration file from JSON.");
            System.out.println(ex);
            System.exit(1);
        }
        catch(FileNotFoundException ex)
        {
            System.out.println(ex);
            System.exit(1);
        }
    }

        /**
     * Saves the configuration file.
     */
    public static void saveConfig() {
        try
        { 
            if(configFile == null || configFile.isEmpty()) {
                throw new FileNotFoundException("File config can not be empty for Onion Router. Please choose a specific router.");
            }

            JsonIO.writeSerializedObject(conf, new File(configFile));
        }
        catch(FileNotFoundException ex)
        {
            System.out.println(ex);
            System.exit(1);
        }
    }


    /**
     * The entry point
     * @param args the command line arguments.
     * @throws IOException 
     * @throws InterruptedException 
     * @throws NoSuchAlgorithmException 
     */
    public static void main(String[] args) throws InterruptedException, IOException, NoSuchAlgorithmException
    {
    
        if (args.length > 2)
            usage();

        processArgs(args); 

        // Assure that this router has a pub/private key pair, 
        // if not 
        //  update the config, 
        //  stop program, 
        //  and print out public key.
        if(conf.getPrivKey() == null || conf.getPrivKey().isEmpty()) {

            Pair<String> keys = ServerCrypto.generateAsymKeys();

            conf.setPrivKey(keys.getSecond());
        
            saveConfig();

            System.out.println("Please update the corresponding client-config.json table with the public key below.");
            System.out.println(keys.getFirst());

            // We don't care about our threads, just crudely shutdown.
            System.exit(0);
        }

        System.out.println("Mack Yack Server built successfully on port: " + conf.getPort() + ".");

        serverService = new ServerService();
    }

    public static ServerConfig getConf() {
        return conf;
    }

    public static Messages getMessages() {
        return messages;
    }

    public static ServerService getServerService() {
        return serverService;
    }

    
}