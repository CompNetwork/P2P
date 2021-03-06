package main.hosts;

import main.config.pod.CommonConfigData;
import main.config.pod.PeerConfigData;
import main.config.reader.CommonConfigReader;
import main.config.reader.PeerConfigReader;
import main.file.ChunkifiedFile;
import main.logger.Logger;
import main.messsage.Message;
import main.unchoking.CalculateHighestUploadingNeighbors;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.*;

public class Peer {

    private String PEERINFO = "PeerConfig/PeerInfo.cfg";
    private String FILEPATH = "PeerConfig/Common.cfg";

    private int peerID;
    private String hostName;
    private int port;
    private ArrayList <ClientThread> connections;
    private ServerSocket sSocket;
    private ChunkifiedFile chunky;
    private Logger logger;
    private Timer time;
    private CalculateHighestUploadingNeighbors calcHighestUploadNeigbor;
    private ArrayList<PeerConfigData> peerConfigDatas;
    private String neighborList;
    private int numberOfPiecesObtained;

    public Set<Integer> getGloballyRequestedSet() {
            return globallyRequestedSet;
    }

    // All the requests for pieces we've made to any peer!
    private Set<Integer> globallyRequestedSet = new HashSet<>();
    CommonConfigData commonConfigData = null;

    public Peer(int peerID, String pathPrefix) throws FileNotFoundException {
        PEERINFO = pathPrefix + PEERINFO;
        FILEPATH = pathPrefix + FILEPATH;
        initCommonConfig();
        PeerConfigReader peerConfigReader = new PeerConfigReader(new File(PEERINFO));
        this.peerConfigDatas = peerConfigReader.getPeerConfigDatas();
        this.peerID = peerID;
        this.hostName = getPeerConfigDataForSelf().hostName;
        this.port = getPeerConfigDataForSelf().listeningPort;
        this.connections = new ArrayList<ClientThread>();
        this.logger = new Logger();
        this.chunky = initFileChunk();
        this.time = new Timer();
        // Read the config file, parse andstore the data.
        this.calcHighestUploadNeigbor = new CalculateHighestUploadingNeighbors(getAllOtherPeers());
        this.neighborList = "";
    }

    public Peer(int peerID) throws FileNotFoundException {
        PEERINFO = "./src/main/hosts/" + PEERINFO;
        FILEPATH = "./src/main/hosts/" + FILEPATH;
        initCommonConfig();
        PeerConfigReader peerConfigReader = new PeerConfigReader(new File(PEERINFO));
        this.peerConfigDatas = peerConfigReader.getPeerConfigDatas();
        this.peerID = peerID;
        this.hostName = getPeerConfigDataForSelf().hostName;
        this.port = getPeerConfigDataForSelf().listeningPort;
        this.connections = new ArrayList<ClientThread>();
        this.logger = new Logger();
        this.chunky = initFileChunk();
        this.time = new Timer();
        // Read the config file, parse andstore the data.
        this.calcHighestUploadNeigbor = new CalculateHighestUploadingNeighbors(getAllOtherPeers());
        this.numberOfPiecesObtained = 0;
    }

    private void initCommonConfig() {
        CommonConfigReader commonConfigReader = null;
        try {
            commonConfigReader = new CommonConfigReader(new File(FILEPATH));
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        }
        commonConfigData = commonConfigReader.getData();
    }

    private List<Integer> getAllOtherPeers() {
        List<Integer> otherPeers = new ArrayList<Integer>();
        for ( PeerConfigData peerConfigData : peerConfigDatas ) {
            if ( !((peerConfigData.peerId) == ((peerID))))  {
                otherPeers.add(peerConfigData.peerId);
            }
        }
        return otherPeers;
    }

    private List<Integer> getAllPeersAboveMe() {
        List<Integer> otherPeers = new ArrayList<Integer>();
        for ( PeerConfigData peerConfigData : peerConfigDatas ) {
            if ( !((peerConfigData.peerId) == ((peerID))))  {
                otherPeers.add(peerConfigData.peerId);
            } else {
                break;
            }
        }
        return otherPeers;

    }

    // Starts P2P process
    public void start() {
        //this.chunky.AvailableChunks();
        this.connect2Peers();
        this.startServer();
    }

    // Connects to all appropriate peers
    public void connect2Peers() {

        try {
            List<PeerConfigData> peers = this.getPeerConnList();
            if (peers.size() == 0) System.out.println("I " + this.peerID + " am the first. No one to connect to.");
            else System.out.println("Connecting with peers " + peers);

            for (PeerConfigData peerConfigData : peers) {
                int peerID = peerConfigData.peerId;

                System.out.println("Handling Peer: " + peerConfigData.toString());

                System.out.println("I am " + this.peerID + " attempting to connect with " + peerID);
                System.out.println("hostname: " + peerConfigData.hostName + " port " + Integer.valueOf(peerConfigData.listeningPort));
                Socket s = new Socket(peerConfigData.hostName, Integer.valueOf(peerConfigData.listeningPort));
                RemotePeer remotePeer = new RemotePeer(peerID,chunky.getChunkCount());
                ClientThread ct = new ClientThread(s, this,remotePeer);
                ct.start();
                this.connections.add(ct);

                System.out.println("Peer " + this.peerID + " has successfully connected with Peer " + peerID + " via socket " + s.toString());
            }

        } catch (UnknownHostException e) {
            System.out.println("Unknown host");
        } catch (IOException e) {
            System.out.println("No I/O");
        }
        updateChokingAndUnchoking();
    }

    // Opens server connects
    public void startServer(){
        try {
            this.sSocket = new ServerSocket(this.port);
            while(true) {

                // Select between accepting new sockets
                System.out.println("Awaiting connections to other peers...");
                Socket s = this.sSocket.accept();
                System.out.println("Peer " + this.peerID + " added new connection: " + s.toString() + " to connection list");
                RemotePeer remotePeer = new RemotePeer(RemotePeer.NO_PEER_ID_YET,chunky.getChunkCount());
                ClientThread ct = new ClientThread(s, this, remotePeer);
                ct.start();

                this.connections.add(ct);
            }
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            try {
                for (ClientThread connection : connections) { connection.close(); }
                this.sSocket.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

    }

    private ChunkifiedFile initFileChunk() {

        String fileSep = System.getProperty("file.separator");
        String[] pathParts = commonConfigData.getFileName().split("/");
        String currentDir = System.getProperty("user.dir");
        String peerLogDir = fileSep + "peer_" + this.peerID + fileSep;
        String filepath = currentDir + peerLogDir + pathParts[pathParts.length-1];

        File theDir = new File(currentDir+peerLogDir);
        if (!theDir.exists()) theDir.mkdir();

        PeerConfigData self = this.getPeerConfigDataForSelf();
        ChunkifiedFile chunkifiedFile = null;
        // Sets the Chunkified File data if this localPeer has the file
        if (self.hasFileOrNot) {
            //TODO: Copy file to path

            String projectDir = System.getProperty("user.dir");
            int pathIndex = System.getProperty("user.dir").indexOf("src");

            if(pathIndex != -1) {
                projectDir = System.getProperty("user.dir").substring(0,pathIndex);
            }

            chunkifiedFile = ChunkifiedFile.GetFromExisingFile(projectDir + "/" + commonConfigData.getFileName(), commonConfigData.getPieceSize(), commonConfigData.getFileSize());
        } else {

            chunkifiedFile = ChunkifiedFile.CreateFile(filepath, commonConfigData.getPieceSize(), commonConfigData.getFileSize());
        }
        return chunkifiedFile;
    }

    // Collects all entries from PeerInfo.cfg that are above our entry in the list!
    private List<PeerConfigData> getPeerConnList(){
        return peerConfigDatas.subList(0,getSelfIndexInPeerConfigDataList());
    }

    // Return ourself in the peer config list!
    private PeerConfigData getPeerConfigDataForSelf() {
        return peerConfigDatas.get(getSelfIndexInPeerConfigDataList());
    }

    // Return the index of our own entry in the peer config list!
    private int getSelfIndexInPeerConfigDataList() {
        for ( int i = 0; i != peerConfigDatas.size(); ++i ) {
            if ( (peerConfigDatas.get(i).peerId) == (this.peerID) ) {
                return i;
            }

        }
        throw new IllegalArgumentException("Error, we are not listed in the peer config list!");

    }

    public ArrayList<ClientThread> getConnections() {
        return connections;
    }

    public String getHostName() {
        return hostName;
    }

    public Logger getLogger(){ return logger;}

    public int getPort() {
        return port;
    }

    public ChunkifiedFile getChunky() {
        return chunky;
    }

    public int getPeerID() {
        return peerID;
    }

    public void sendMessageToAllRemotePeers(Message message) throws IOException {
        for (ClientThread thread : connections) {
            thread.sendMessage(message);
        }
    }
    public void sendHaveMessageToAllRemotePeers(int index)  throws IOException {
        // Do not reuse the existing message, multi threading problems!!
        Message m = new Message();
        m.mutateIntoHave(index);
        this.sendMessageToAllRemotePeers(m);
    }

    // For each client thread (remote peer), determine if we are still interested in them.
    // If we are not, send a uninterested message.
    // We do not check if a peer is uninteresting before hand. If a peer was uninteresting before hand.
    public void sendUninterestedToAllUninterestingPeers() throws IOException {
        Message uninterested = new Message();
        uninterested.mutateIntoUnInterested();
        for (ClientThread thread : connections) {
            if (!thread.isRemotePeerInteresting() ) {
                thread.remotePeer.setInterested(false);
                thread.sendMessage(uninterested);
            }
        }
    }

    public void informOfReceivedPiece(int peerID, int sizeOfPiece) {
        calcHighestUploadNeigbor.receivedNewPackageFromNeighbor(peerID,sizeOfPiece);


    }

    public void updateChokingAndUnchoking(){

        //unchoking
        time.scheduleAtFixedRate(new TimerTask() {
            public void run(){
                ArrayList<Integer> toUnchoke = calcHighestUploadNeigbor.getKBestUploaders(commonConfigData.getNumberPreferrredNeighbors()); //get k specified from file
                System.out.println("Inside first scheduled task");
                System.out.println("Unchoking peers: " + Arrays.toString(toUnchoke.toArray()));
                neighborList = Arrays.toString(toUnchoke.toArray());
                //logger.changePreferredNeighborsLog(peerID);

                for(ClientThread thread : connections){
                    boolean wasUnchoked = false;
                    for(int unc : toUnchoke){
                        if(thread.remotePeer.getPeerID() == (unc)){
                            wasUnchoked = true;
                            try{

                                thread.sendUnchoke(new Message());
                            }
                            catch(Exception e){
                                System.out.println("Failed to send unchoke.");
                            }
                        }
                    }
                    if(!wasUnchoked){

                        try {
                            thread.sendChoke(new Message());
                        }
                        catch(Exception e){
                            System.out.println("Failed to send choke");
                        }
                    }


                }

                logger.changePreferredNeighborsLog(peerID,neighborList);


                //clears out map.
                calcHighestUploadNeigbor.clear();
            }

        }, commonConfigData.getUnchokeInterval()*1000,commonConfigData.getUnchokeInterval()*1000);   //replace this hardcoded number with fileSpecifiedNum

        //optimistically unchoking
        //every m seconds
        //if peer is interested AND choked,
        //unchoke RANDOM peer from those that meet criteria
        time.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                ArrayList <ClientThread> possibleUnchoking = new ArrayList<ClientThread>();
                System.out.println("Inside second scheduled task");
                for (ClientThread thread : connections) {
                    if (thread.remotePeer.getChoked() && thread.remotePeer.getInterested() ) {
                        //add to list
                        possibleUnchoking.add(thread);
                    }
                }

                //randomly choose 1 to unchoke
                Random randomGenerator = new Random();

                //peer sends out unchoke message
                System.out.println("Size of possibleUnchoking:" + possibleUnchoking.size());
                if(possibleUnchoking.size() != 0) {
                    int unchokeIndex = randomGenerator.nextInt(possibleUnchoking.size());
                    possibleUnchoking.get(unchokeIndex).remotePeer.setChoked(false);
                    Message unchoke = new Message();
                    unchoke.mutateIntoUnChoke();
                    //I have no idea why it forced me to put it into a try/catch
                    // Because sending a message can fail if the network fails.
                    try {
                        possibleUnchoking.get(unchokeIndex).sendMessage(unchoke);
                        logger.changeOfOptimisticallyUnchockedNeighborLog(peerID,possibleUnchoking.get(unchokeIndex).remotePeer.getPeerID());
                    } catch (IOException e) {
                        System.out.println("Could not send unchoking message in updateChokingUnchoking");
                    }
                }
                // Do not clear the map for this one actually, we aren't using the map here!
                // We only clear the map when we pick based on upload rate.
            }
        }, commonConfigData.getOptimisticUnchokeInterval()*1000,commonConfigData.getOptimisticUnchokeInterval()*1000);           //replace this hardcoded number with fileSpecifiedNum
    }

    // Check all our peers, check if they are complete, if they all are, exit!
    public void checkIfEveryoneIsDone() {
        if ( connections.size() != getAllOtherPeers().size()) {
            // Don't abort exiting, but do check this.
            System.out.println("Error in connection size!!! Reported is " + connections.size() + " But expected is " + getAllOtherPeers().size());
            System.out.println("----------------------------------------THIS IS BAD----------------------------------");
        }
        for ( ClientThread peer : connections ) {
            if ( !peer.isPeerDone() ) {
                // If a peer is not done, return
                System.out.println("we are not closing because peer " + peer.remotePeer.getPeerID());
                return;
            }
        }
        // We are connected to all peers, and they are done.
        // Double check we are done
        if ( !chunky.hasAllChunks() ) {
            // IF we are missing chunks, return
            System.out.println("we are not finishing because we do not have all the chunks!");
            return;
        }
        // We are connectted to all peers
        // All peers are done
        // We are done
        // Program over, terminate.
        System.out.println("All peers and self complete, terminating program");
        System.exit(0);

    }

    void increaseNumberOfPiecesTracked(){
        numberOfPiecesObtained++;
    }
    int getNumberOfPiecesObtained(){
        return numberOfPiecesObtained;
    }
}
