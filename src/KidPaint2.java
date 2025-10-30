import javafx.application.Application;
import javafx.stage.Stage;
import java.io.IOException; // ADD THIS IMPORT

public class KidPaint2 extends Application {
    final static String title = "KidPaint 2.0";
    final static int SERVER_PORT = 12345;

    PaintServerHost server;

    @Override
    public void start(Stage stage) throws Exception {
        try {
            // 1. Get username
            GetNameDialog nameDialog = new GetNameDialog(title);
            String username = nameDialog.getPlayername();

            if (username == null || username.trim().isEmpty()) {
                System.out.println("No username entered. Exiting.");
                return;
            }
            stage.setTitle(title + " - " + username);


            // 2. Show Host or Join dialog
            StudioDialog studioDialog = new StudioDialog(stage);
            String choice = studioDialog.showAndWait();

            if (choice.equals("HOST")) {
                // --- HOST LOGIC ---
                System.out.println("Starting server...");
                // Pass username as the studio name
                server = new PaintServerHost(SERVER_PORT, username + "'s Studio");
                Thread serverThread = new Thread(server);
                serverThread.start();

                MainWindow mainWindow = new MainWindow(stage, username, "127.0.0.1", SERVER_PORT);

            } else if (choice.equals("JOIN")) {
                // --- JOIN LOGIC ---
                System.out.println("Searching for studios...");

                StudioListDialog listDialog = new StudioListDialog(stage);
                StudioInfo selectedStudio = listDialog.showAndWait();

                if (selectedStudio != null) {
                    // If user selected a studio, join it
                    System.out.println("Joining studio: " + selectedStudio.getStudioName());
                    MainWindow mainWindow = new MainWindow(stage, username,
                            selectedStudio.getIpAddress(),
                            selectedStudio.getPort());
                } else {
                    // If user closed the list, exit
                    System.out.println("No studio selected. Exiting.");
                    System.exit(0);
                }
            } else {
                // User closed the Host/Join dialog
                System.exit(0);
            }

        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    @Override
    public void stop() {
        if (server != null) {
            server.stopServer(); // Shut down the server thread
        }
        System.exit(0);
    }
}