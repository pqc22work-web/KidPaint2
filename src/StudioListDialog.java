import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.stage.Modality;
import javafx.stage.Stage;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;

public class StudioListDialog {
    @FXML
    private Label lblStatus;
    @FXML
    private ListView<StudioInfo> listView;
    @FXML
    private Button btnJoin;

    private Stage stage;
    private ObservableList<StudioInfo> studioList = FXCollections.observableArrayList();
    private StudioInfo selectedStudio = null;

    // Use a specific port for UDP discovery
    final static int DISCOVERY_PORT = 12346;
    final static String DISCOVERY_REQUEST = "KIDPAINT_DISCOVERY_REQUEST";
    final static String DISCOVERY_REPLY_HEADER = "KIDPAINT_STUDIO:";

    public StudioListDialog(Stage owner) throws IOException {
        FXMLLoader loader = new FXMLLoader(getClass().getResource("StudioListDialog.fxml"));
        loader.setController(this);
        Parent root = loader.load();

        stage = new Stage();
        stage.initOwner(owner);
        stage.initModality(Modality.APPLICATION_MODAL);
        stage.setTitle("Find Studios");
        stage.setScene(new Scene(root));

        listView.setItems(studioList);

        // Enable Join button only when a studio is selected
        listView.getSelectionModel().selectedItemProperty().addListener((obs, oldVal, newVal) -> {
            btnJoin.setDisable(newVal == null);
        });

        btnJoin.setOnAction(e -> onJoinClick());
    }

    private void onJoinClick() {
        selectedStudio = listView.getSelectionModel().getSelectedItem();
        stage.close();
    }

    /**
     * Shows the dialog, starts discovery, and waits for selection.
     */
    public StudioInfo showAndWait() {
        // Start discovery on a new thread so it doesn't block the UI
        Thread discoveryThread = new Thread(this::discoverServers);
        discoveryThread.start();

        stage.showAndWait(); // This blocks until the user clicks Join or closes
        return selectedStudio;
    }

    /**
     * This method runs on a separate thread.
     * It sends a UDP broadcast and listens for replies for 3 seconds.
     */
    private void discoverServers() {
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setBroadcast(true);
            socket.setSoTimeout(3000); // 3-second timeout for replies

            // 1. Send broadcast request
            byte[] sendData = DISCOVERY_REQUEST.getBytes();
            DatagramPacket sendPacket = new DatagramPacket(sendData, sendData.length, InetAddress.getByName("255.255.255.255"), DISCOVERY_PORT);
            socket.send(sendPacket);
            System.out.println("Sent UDP broadcast request.");

            // 2. Listen for replies
            while (true) {
                byte[] receiveBuffer = new byte[1024];
                DatagramPacket receivePacket = new DatagramPacket(receiveBuffer, receiveBuffer.length);
                socket.receive(receivePacket); // This will block until a packet is received or timeout

                // We got a reply, parse it
                String reply = new String(receivePacket.getData(), 0, receivePacket.getLength());
                System.out.println("Received UDP reply: " + reply);

                String[] parts = reply.split(":");
                // Check if it's a valid reply: "KIDPAINT_STUDIO:StudioName:12345"
                if (parts.length == 3 && parts[0].equals(DISCOVERY_REPLY_HEADER.substring(0, DISCOVERY_REPLY_HEADER.length()-1))) {
                    String studioName = parts[1];
                    String ip = receivePacket.getAddress().getHostAddress();
                    int port = Integer.parseInt(parts[2]);

                    StudioInfo info = new StudioInfo(studioName, ip, port);

                    // Update UI on the JavaFX Application Thread
                    Platform.runLater(() -> {
                        if (!studioList.stream().anyMatch(s -> s.getIpAddress().equals(ip))) {
                            studioList.add(info);
                            lblStatus.setText("Found " + studioList.size() + " studio(s)");
                        }
                    });
                }
            }
        } catch (SocketTimeoutException e) {
            // This is expected! It means the 3-second discovery is over.
            System.out.println("Discovery finished.");
            Platform.runLater(() -> {
                if (studioList.isEmpty()) {
                    lblStatus.setText("No studios found.");
                } else {
                    lblStatus.setText("Select a studio to join.");
                }
            });
        } catch (IOException e) {
            e.printStackTrace();
            Platform.runLater(() -> lblStatus.setText("Error during discovery."));
        }
    }
}