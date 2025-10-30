import java.io.IOException;
import java.net.Socket;

public class WorkerThread extends Thread {
    private String username;
    private PaintServerHost server;
    private Socket socket;

    public WorkerThread(Socket socket, PaintServerHost server) { // <-- NEW CLASS
        this.socket = socket;
        this.server = server;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getUsername() {
        return username;
    }

    @Override
    public void run() {
        try {
            server.serve(socket, this);
        } catch (IOException e) {
            System.out.println("Disconnected!");

            synchronized (server.clientMap) {
                server.clientMap.remove(socket);
            }
        }
    }
}
