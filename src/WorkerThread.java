import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Socket;

public class WorkerThread extends Thread {
    private String username;
    private PaintServerHost server;
    private Socket socket;
    private DataOutputStream out; // Store the output stream

    // Update constructor
    public WorkerThread(Socket socket, PaintServerHost server, DataOutputStream out) {
        this.socket = socket;
        this.server = server;
        this.out = out;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getUsername() {
        return username;
    }

    public DataOutputStream getOutputStream() {
        return out;
    }

    @Override
    public void run() {
        try {
            server.serve(socket, this);
        } catch (IOException e) {
            System.out.println("Client disconnected: " + (username != null ? username : socket.getInetAddress()));
        } finally {
            // This is the cleanup logic!
            // 1. Remove user from server's map
            synchronized (server.clientThreads) {
                server.clientThreads.remove(socket);
            }
            // 2. Broadcast that this user has left
            if (username != null) {
                server.broadcastSystemMessage(server.USER_LEFT, username);
            }
            // 3. Close the socket
            try {
                socket.close();
            } catch (IOException e) {
                // ignore
            }
        }
    }
}