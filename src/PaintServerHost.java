import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Random;
import java.util.Timer;
import java.util.TimerTask;

/**
 * This class runs as a server on a separate thread
 * inside a host client's application.
 */
public class PaintServerHost implements Runnable {

    // Store threads, not just streams
    HashMap<Socket, WorkerThread> clientThreads = new HashMap<>();
    int[][] data = new int[100][100];

    // Message Type Constants
    final int NAME = 0;
    final int PIXELS = 1;
    final int MESSAGE = 2;
    final int FULL_SKETCH = 3;
    final int FULL_SKETCH_UPDATE = 4;
    final int USER_JOINED = 5;
    final int FULL_USER_LIST = 6;
    final int USER_LEFT = 7;
    final int WHISPER_MESSAGE = 8;
    final int GAME_INFO = 9; // Theme and Timer
    final int GAME_OVER = 10; // Time's up

    private int port;
    private ServerSocket serverSocket;
    private String studioName;
    private Thread udpListenerThread;
    private String currentTheme = "Waiting...";
    private int timeRemaining = 300; // 5 minutes (in seconds)
    private Timer gameTimer;
    private boolean gameStarted = false;

    /**
     * Inner class Point
     */
    class Point {
        int x, y;

        public Point(int x, int y) {
            this.x = x;
            this.y = y;
        }
    }

    /**
     * Constructor sets port and studio name.
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
        loadThemesAndStartGame();
    }

    /**
     * This is the main loop for the TCP server thread.
     */
    @Override
    public void run() {
        // Start the UDP listener
        UdpBroadcastListener udpListener = new UdpBroadcastListener();
        udpListenerThread = new Thread(udpListener);
        udpListenerThread.start();

        try {
            serverSocket = new ServerSocket(port);
            System.out.println("TCP Server started on port: " + port);

            while (true) {
                Socket socket = serverSocket.accept();
                System.out.println("New client connected!");

                // Create the stream and worker, then store the worker
                DataOutputStream out = new DataOutputStream(socket.getOutputStream());
                WorkerThread thread = new WorkerThread(socket, this, out);
                synchronized (clientThreads) {
                    clientThreads.put(socket, thread);
                }
                thread.start();
            }
        } catch (IOException e) {
            System.out.println("Server was shut down: " + e.getMessage());
        }
    }

    /**
     * Stops the server by closing the ServerSocket and interrupting the UDP listener.
     */
    public void stopServer() {
        if (gameTimer != null) gameTimer.cancel();
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

    // --- All server logic methods below ---

    void serve(Socket socket, WorkerThread thread) throws IOException {
        DataInputStream in = new DataInputStream(socket.getInputStream());
        DataOutputStream out = thread.getOutputStream();

        while (true) {
            int type = in.read();
            switch (type) {
                case NAME: //NAME
                    String newUsername = receiveName(in);
                    thread.setUsername(newUsername);
                    broadcastSystemMessage(USER_JOINED, newUsername);
                    sendFullUserList(out);
                    sendFullSketch(out);
                    sendGameInfo(out); // Send theme and time
                    break;
                case PIXELS: //PIXELS
                    receivePixels(in);
                    break;
                case MESSAGE: //MESSAGE
                    receiveMsg(in, thread.getUsername());
                    break;
                case WHISPER_MESSAGE:
                    receiveWhisper(in, thread);
                    break;
                case FULL_SKETCH_UPDATE:
                    receiveFullSketchUpdate(in);
                    break;
            }
        }
    }

    void loadThemesAndStartGame() {
        try {
            List<String> themes = Files.readAllLines(new File("themes.txt").toPath());
            if (themes.isEmpty()) {
                currentTheme = "No themes found!";
            } else {
                currentTheme = themes.get(new Random().nextInt(themes.size()));
            }
        } catch (IOException e) {
            System.out.println("Could not load themes.txt: " + e.getMessage());
            currentTheme = "Error!";
        }
        gameStarted = true;
        gameTimer = new Timer();
        gameTimer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                if (timeRemaining > 0) {
                    timeRemaining--;
                } else {
                    broadcastSimpleGameMessage(GAME_OVER);
                    System.out.println("Game Over!");
                    cancel();
                }
            }
        }, 1000, 1000);
    }

    void receiveMsg(DataInputStream in, String username) throws IOException {
        int size = in.readInt();
        byte[] buffer = new byte[size];
        in.read(buffer, 0, size);
        String text = username + ": " + new String(buffer, 0, size);
        forwardMsg(text.getBytes());
    }

    void forwardMsg(byte[] buffer) {
        synchronized (clientThreads) {
            for (WorkerThread worker : clientThreads.values()) {
                try {
                    DataOutputStream out = worker.getOutputStream();
                    out.write(MESSAGE);
                    out.writeInt(buffer.length);
                    out.write(buffer, 0, buffer.length);
                    out.flush();
                } catch (IOException ex) {
                    // This kid left
                }
            }
        }
    }

    void sendGameInfo(DataOutputStream out) throws IOException {
        out.write(GAME_INFO);
        out.writeUTF(currentTheme);
        out.writeInt(timeRemaining);
        out.flush();
    }

    void broadcastSimpleGameMessage(int type) {
        synchronized (clientThreads) {
            for (WorkerThread worker : clientThreads.values()) {
                try {
                    DataOutputStream out = worker.getOutputStream();
                    out.write(type);
                    out.flush();
                } catch (IOException e) {
                    // Failed to send
                }
            }
        }
    }

    void receiveWhisper(DataInputStream in, WorkerThread sender) throws IOException {
        String targetUsername = in.readUTF();
        String message = in.readUTF();
        String senderUsername = sender.getUsername();
        WorkerThread target = null;
        synchronized (clientThreads) {
            for (WorkerThread worker : clientThreads.values()) {
                if (worker.getUsername().equals(targetUsername)) {
                    target = worker;
                    break;
                }
            }
        }
        if (target != null) {
            String toTargetMsg = "(Whisper from " + senderUsername + "): " + message;
            sendMessageToClient(target.getOutputStream(), toTargetMsg);
            String toSenderMsg = "(Whisper to " + targetUsername + "): " + message;
            sendMessageToClient(sender.getOutputStream(), toSenderMsg);
        } else {
            String errorMsg = "*** User '" + targetUsername + "' not found. ***";
            sendMessageToClient(sender.getOutputStream(), errorMsg);
        }
    }

    void sendMessageToClient(DataOutputStream out, String message) {
        byte[] buffer = message.getBytes();
        try {
            out.write(MESSAGE);
            out.writeInt(buffer.length);
            out.write(buffer, 0, buffer.length);
            out.flush();
        } catch (IOException ex) {
            System.out.println("Failed to send private message.");
        }
    }

    void receivePixels(DataInputStream in) throws IOException {
        int color = in.readInt();
        int len = in.readInt();
        LinkedList<Point> pixels = new LinkedList<>();
        for (int i = 0; i < len; i++) {
            int x = in.readInt();
            int y = in.readInt();
            pixels.add(new Point(x, y));
            data[y][x] = color;
        }
        forwardPixels(color, pixels);
    }

    void forwardPixels(int color, LinkedList<Point> pixels) {
        synchronized (clientThreads) {
            for (WorkerThread worker : clientThreads.values()) {
                try {
                    DataOutputStream out = worker.getOutputStream();
                    out.write(PIXELS);
                    out.writeInt(color);
                    out.writeInt(pixels.size());
                    for (Point p : pixels) {
                        out.writeInt(p.x);
                        out.writeInt(p.y);
                    }
                    out.flush();
                } catch (IOException ex) {
                    // someone disconnected
                }
            }
        }
    }

    String receiveName(DataInputStream in) throws IOException {
        int len = in.readInt();
        byte[] buffer = new byte[len];
        in.read(buffer, 0, len);
        String name = new String(buffer, 0, len);
        System.out.println(name);
        return name;
    }

    void sendFullSketch(DataOutputStream out) throws IOException {
        System.out.println("Sending full sketch to new client...");
        out.write(FULL_SKETCH);
        out.writeInt(data.length);
        for (int row = 0; row < data.length; row++) {
            for (int col = 0; col < data[0].length; col++) {
                out.writeInt(data[row][col]);
            }
        }
        out.flush();
        System.out.println("Full sketch sent.");
    }

    void receiveFullSketchUpdate(DataInputStream in) throws IOException {
        int size = in.readInt();
        if (size != data.length) return;
        for (int row = 0; row < size; row++) {
            for (int col = 0; col < size; col++) {
                data[row][col] = in.readInt();
            }
        }
        broadcastFullSketch();
    }

    void broadcastFullSketch() {
        synchronized (clientThreads) {
            for (WorkerThread worker : clientThreads.values()) {
                try {
                    DataOutputStream out = worker.getOutputStream();
                    out.write(FULL_SKETCH_UPDATE);
                    out.writeInt(data.length);
                    for (int row = 0; row < data.length; row++) {
                        for (int col = 0; col < data[0].length; col++) {
                            out.writeInt(data[row][col]);
                        }
                    }
                    out.flush();
                } catch (IOException ex) {
                    // Failed to broadcast
                }
            }
        }
    }

    void sendFullUserList(DataOutputStream out) throws IOException {
        System.out.println("Sending full user list...");
        out.write(FULL_USER_LIST);
        synchronized (clientThreads) {
            out.writeInt(clientThreads.size());
            for (WorkerThread worker : clientThreads.values()) {
                String name = worker.getUsername() != null ? worker.getUsername() : "Joining...";
                out.writeUTF(name);
            }
        }
        out.flush();
        System.out.println("User list sent.");
    }

    void broadcastSystemMessage(int type, String message) {
        System.out.println("Broadcasting system message: " + type + " / " + message);
        synchronized (clientThreads) {
            for (WorkerThread worker : clientThreads.values()) {
                if (type == USER_JOINED && worker.getUsername().equals(message)) {
                    continue;
                }
                try {
                    DataOutputStream out = worker.getOutputStream();
                    out.write(type);
                    out.writeUTF(message);
                    out.flush();
                } catch (IOException e) {
                    // Failed to send
                }
            }
        }
    }

    /**
     * This inner class listens for UDP broadcasts from clients
     * looking for studios.
     */
    class UdpBroadcastListener implements Runnable {
        final static int DISCOVERY_PORT = 12346;
        final static String DISCOVERY_REQUEST = "KIDPAINT_DISCOVERY_REQUEST";
        final static String DISCOVERY_REPLY_HEADER = "KIDPAINT_STUDIO:";

        @Override
        public void run() {
            try (DatagramSocket socket = new DatagramSocket(DISCOVERY_PORT)) {
                System.out.println("UDP Discovery listener started on port " + DISCOVERY_PORT);
                while (!Thread.currentThread().isInterrupted()) {
                    byte[] receiveBuffer = new byte[1024];
                    DatagramPacket receivePacket = new DatagramPacket(receiveBuffer, receiveBuffer.length);
                    socket.receive(receivePacket);
                    String message = new String(receivePacket.getData(), 0, receivePacket.getLength());
                    if (message.equals(DISCOVERY_REQUEST)) {
                        System.out.println("Received discovery request from " + receivePacket.getAddress());
                        String reply = DISCOVERY_REPLY_HEADER + studioName + ":" + port;
                        byte[] sendData = reply.getBytes();
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
}