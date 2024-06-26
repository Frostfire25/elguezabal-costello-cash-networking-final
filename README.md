# MackYack: 
## Anonymous Messaging Service using Onion Routing 

<p style="align: center;">

 ![MackYack Logo](./images/logo.png) 

</p>

## Overview

MackYack is an anonymous messaging board system designed to prioritize user privacy and confidentiality. It leverages Onion Routing, a technique that routes data through multiple intermediate nodes in a layered manner, to ensure anonymity and prevent any single node from knowing both the sender and recipient of a message.

## Features

- **Anonymous Messaging**: Users can send and receive messages on the MackYack board without revealing their identity.
- **Client-Server Model**: MackYack operates on a client-server architecture, allowing clients to interact with the messaging board via the server.
- **Onion-Proxy**: The Onion Proxy (OP) is a crucial component of MackYack, utilized by clients to access the Onion Routing overlay network.
- **Onion Routing**: An Onion Proxy is employed to facilitate communication between clients and the Onion Routing network, ensuring secure message relay and reception.
- **Application Layer Messages**: MackYack supports various application layer messages including PutRequest, PutResponse, GetRequest, and GetResponse, enabling efficient communication between clients and the server.
- **Configuration**: The system includes configuration files such as routers.json, serverConfig.json, clientConfig.json, and router_k.json for seamless initialization and operation.

## Video Instructions

`TODO, will post link when completed.`

## Build
---
To build the project first make sure you have the correct libraries to build the jar. They can be found in the [lib](/lib/) folder.

First, run the `ant clean` command to remove old builds.
Then, run `ant` to build the `mackyack_client`, `mackyack_server`, and `onionrouter`.
| :zap:        Please make sure  bcprov-ext-jdk18on-172 is in the same directory as the build files when running!   |
|-----------------------------------------|


## Initiation
--- 
Before initiation, all configs in [config](./configs/) should be base implemented.
Reference the Configs section 

All commands should be ran in the root directory of the project

1. Build & Run the `Onion Routers`
   - First need to run k amount of Onion Routers (OR), where each value of k represents a unique Onion Router.
   - Run the following command for a specific Onion Router k. Replace $ with k.
   - `java -jar .\dist\onionrouter.jar --config .\configs\router-$.json`
   - You will receive a public key, on the initiation run. Update the `routers.json` public configuration accordingly.
   - To run the Onion Router, simply run the `java -jar` command again.

2. Build & Run the `Application Server`
   - Run the following command to run the MackYack application server.
   - `java -jar .\dist\mackyack_server.jar --config .\configs\server-config.json`
   - On initiation you will receive a public key, update the `client-config.json` file accordingly.
  
3. Build & Run the `Application Client and Onion Proxy`
    - Run the following command to start the Application Client and Onion Proxy
    - `java -jar .\dist\mackyack_client.jar --config .\configs\client-config.json`
    - Issue commands into the REPL loop for results and information.

## Configs
---
### routers.json
---
Contains information on the onion routers IP / port combinations, as well as their public keys.  \
Example:
```
{
    routers: [
        {
            addr: "127.0.0.1",
            port: 5000,
            pubKey: "<node-pub-key>"
        },
        {
            addr: "127.0.0.1",
            port: 5003,
            pubKey: "<node-pub-key>"
        }
    ]
}
```

### serverConfig.json
---
Contains configuration information for the server to initialize with.  \
Example:
```
{
    port: 5010,
    privKey: "<private-key>",
    messagesPath: "./configs/messages.json"
}
```

### clientConfig.json
---
Contains configuration information for the client to initialize with.  \
Example:
```
{
    addr: "127.0.0.1",
    port: 5000,
    serverAddr: "127.0.0.1",
    serverPort: 5010,
    serverPubKey: "<server-pub-key>",
    routersPath: "example-configs/routers.json"
}
```

### messages.json
---
Contains messages and timestamps stored on the board for the server to reference at startup + write to on each Put request.  \
Example:
```
{
    messages: [
        {
            data: "Hello world.",
            timestamp: "2024/05/07 00:24:12"
        },
        {
            data: "This is Alice, saying hello from the client!",
            timestamp: "2024/05/07 00:25:09"
        }
    ]
}
```

### router_k.json
---
Contains configuration information for the router to initialize with.  \
Example:
```
{
    addr: "127.0.0.1",
    port: 5000,
    privKey: "<private-key>",
    verbose: "false"
}
```

## Todo
---
 - Implement public key cryptography for messages between the Exit OR and Web Service.
 - Adjust Onion Proxy to become a flexible API that accepts JSON in any format and works with Java TCP Sockets.
 - Implement fault tolerance for Onion Router nodes.
 - Create an Authority to handle distribution of Onion Routers to a proxy.
 - Record guided installation and setup video.