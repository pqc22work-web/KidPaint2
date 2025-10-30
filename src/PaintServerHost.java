import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.HashMap;
import java.util.LinkedList;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;

/**
 * This class runs as a server on a separate thread
 * inside a host client's application.
 */
public class PaintServerHost implements Runnable {

    HashMap<Socket, DataOutputStream> clientMap = new HashMap<>();
    int [][] data = new int[100][100];

    // Message Type Constants
    final int NAME = 0;
    final int PIXELS = 1;
    final int MESSAGE = 2;
    final int FULL_SKETCH = 3;
    final int FULL_SKETCH_UPDATE = 4;

    private int port;
    private ServerSocket serverSocket;
    private String studioName;
    private Thread udpListenerThread;

    /**
     * Inner class Point
     */
    class Point{
        int x, y;
        public Point(int x, int y) {
            this.x = x;
            this.y = y;
        }
    }

    /**
     * Constructor just sets the port.
     * The server socket is not created yet.
     */
    public PaintServerHost(int port, String studioName) {
        this.port = port;
        this.studioName = studioName;
        // Initialize the canvas as empty (black)
        for (int row = 0; row < data.length; row++) {
            for (int col = 0; col < data[0].length; col++) {
                data[row][col] = 0; // 0 for black
            }
        }
    }

    /**
     * This is the main loop for the server thread.
     */
    @Override
    public void run() {
        UdpBroadcastListener udpListener = new UdpBroadcastListener();
        udpListenerThread = new Thread(udpListener);
        udpListenerThread.start();
        try {
            serverSocket = new ServerSocket(port);
            System.out.println("Server started on port: " + port);
            while(true){
                Socket socket = serverSocket.accept();
                System.out.println("New client connected!");

                DataOutputStream out = new DataOutputStream(socket.getOutputStream());
                synchronized (clientMap) {
                    clientMap.put(socket, out);
                }

                // Create a worker thread for this client
                WorkerThread thread = new WorkerThread(socket, this);
                thread.start();
            }
        } catch (IOException e) {
            System.out.println("Server was shut down: " + e.getMessage());
        }
    }

    /**
     * Stops the server by closing the ServerSocket.
     * This will cause the .accept() loop to throw an exception.
     */

    public void stopServer() {
        if (udpListenerThread != null) {
            udpListenerThread.interrupt(); // Stop the UDP listener
        }
        try {
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
            }
        } catch (IOException e) {
            // ignore
        }
    }

    class UdpBroadcastListener implements Runnable {
        final static int DISCOVERY_PORT = 12346;
        final static String DISCOVERY_REQUEST = "KIDPAINT_DISCOVERY_REQUEST";
        final static String DISCOVERY_REPLY_HEADER = "KIDPAINT_STUDIO:";

        @Override
        public void run() {
            // Try-with-resources to ensure socket is closed
            try (DatagramSocket socket = new DatagramSocket(DISCOVERY_PORT)) {
                System.out.println("UDP Discovery listener started on port " + DISCOVERY_PORT);

                while (!Thread.currentThread().isInterrupted()) {
                    byte[] receiveBuffer = new byte[1024];
                    DatagramPacket receivePacket = new DatagramPacket(receiveBuffer, receiveBuffer.length);
                    socket.receive(receivePacket); // Block until a packet is received

                    String message = new String(receivePacket.getData(), 0, receivePacket.getLength());

                    // If we get the correct request...
                    if (message.equals(DISCOVERY_REQUEST)) {
                        System.out.println("Received discovery request from " + receivePacket.getAddress());

                        // ...reply with our studio name and TCP port
                        String reply = DISCOVERY_REPLY_HEADER + studioName + ":" + port;
                        byte[] sendData = reply.getBytes();

                        // Send the reply back to where the request came from
                        DatagramPacket sendPacket = new DatagramPacket(sendData, sendData.length,
                                receivePacket.getAddress(), receivePacket.getPort());
                        socket.send(sendPacket);
                    }
                }
            } catch (IOException e) {
                if (Thread.currentThread().isInterrupted()) {
                    System.out.println("UDP Listener stopped.");
                } else {
                    System.out.println("UDP Listener error: " + e.getMessage());
                }
            }
        }
    }

    // --- All original server logic methods below ---

    void serve(Socket socket, WorkerThread thread) throws IOException {
        DataInputStream in = new DataInputStream(socket.getInputStream());

        // GET THE CORRECT 'out' STREAM FROM THE MAP
        DataOutputStream out;
        synchronized(clientMap) {
            out = clientMap.get(socket);
        }

        if (out == null) {
            System.out.println("Error: Could not find output stream for client.");
            socket.close(); // Close the connection
            return;
        }

        while(true){
            int type = in.read();
            switch(type){
                case NAME: //NAME
                    thread.setUsername(receiveName(in));
                    sendFullSketch(out);
                    break;
                case PIXELS: //PIXELS
                    receivePixels(in);
                    break;
                case MESSAGE: //MESSAGE
                    receiveMsg(in, thread.getUsername());
                    break;
                case FULL_SKETCH_UPDATE:
                    System.out.println("Receiving full sketch update from " + thread.getUsername());
                    receiveFullSketchUpdate(in);
                    break;
            }
        }
    }

    void receiveMsg(DataInputStream in, String username) throws IOException {
        int size = in.readInt();

        byte[] buffer = new byte[size];
        in.read(buffer, 0, size);

        System.out.println(new String(buffer, 0, size));
        String text = username + ": " + new String(buffer, 0, size);

        forwardMsg(text.getBytes());
    }

    void forwardMsg(byte[] buffer) {
        synchronized (clientMap) {
            for (DataOutputStream out : clientMap.values()) {
                try {
                    out.write(MESSAGE);   //Datatype 2 = message
                    out.writeInt(buffer.length);
                    out.write(buffer, 0, buffer.length);
                    out.flush();
                } catch (IOException ex) {
                    System.out.println("This kid left!");
                }
            }
        }
    }

    void receivePixels(DataInputStream in) throws IOException {
        int color = in.readInt();
        int len = in.readInt();

        LinkedList<Point> pixels = new LinkedList<>();

        for (int i=0; i<len; i++){
            int x = in.readInt();
            int y = in.readInt();

            pixels.add(new Point(x, y));

            data[y][x] = color;
        }

        forwardPixels(color, pixels);
    }

    void forwardPixels(int color, LinkedList<Point> pixels) {
        synchronized (clientMap) {
            for (DataOutputStream out : clientMap.values()) {
                try {
                    out.write(PIXELS);
                    out.writeInt(color);
                    out.writeInt(pixels.size());
                    for (Point p : pixels) {
                        out.writeInt(p.x);
                        out.writeInt(p.y);
                    }
                    out.flush();
                } catch (IOException ex) {
                    System.out.println("someone disconnected");
                }
            }
        }
    }

    String receiveName(DataInputStream in) throws IOException {
        int len = in.readInt(); //read the length of username
        byte[] buffer = new byte[len]; //create buffer
        in.read(buffer,0,len); //read len bytes into buffer

        System.out.println(new String(buffer,0,len)); //print the username
        return new String(buffer, 0, len);
    }

    void sendFullSketch(DataOutputStream out) throws IOException {
        System.out.println("Sending full sketch to new client...");
        out.write(FULL_SKETCH);
        out.writeInt(data.length); // Send dimension (100)

        for (int row = 0; row < data.length; row++) {
            for (int col = 0; col < data[0].length; col++) { // Assuming square
                out.writeInt(data[row][col]);
            }
        }
        out.flush();
        System.out.println("Full sketch sent.");
    }

    void receiveFullSketchUpdate(DataInputStream in) throws IOException {
        int size = in.readInt();
        if (size != data.length) {
            System.out.println("Received sketch with incompatible size. Ignoring.");
            return;
        }

        // Read the new sketch into the server's 'data' array
        for (int row = 0; row < size; row++) {
            for (int col = 0; col < size; col++) {
                data[row][col] = in.readInt();
            }
        }
        System.out.println("Server data updated. Broadcasting to all clients.");

        // Now, broadcast this new full sketch to everyone
        broadcastFullSketch();
    }

    void broadcastFullSketch() {
        synchronized (clientMap) {
            System.out.println("Broadcasting full sketch to " + clientMap.size() + " clients.");
            for (DataOutputStream out : clientMap.values()) {
                try {
                    out.write(FULL_SKETCH_UPDATE);
                    out.writeInt(data.length); // Send dimension (100)

                    for (int row = 0; row < data.length; row++) {
                        for (int col = 0; col < data[0].length; col++) {
                            out.writeInt(data[row][col]);
                        }
                    }
                    out.flush();
                } catch (IOException ex) {
                    System.out.println("Failed to broadcast sketch to a client.");
                }
            }
        }
        System.out.println("Broadcast complete.");
    }
}