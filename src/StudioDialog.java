import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.stage.Modality;
import javafx.stage.Stage;

import java.io.IOException;

public class StudioDialog {
    @FXML
    private Button btnHost;
    @FXML
    private Button btnJoin;

    private Stage stage;
    private String selection = "";

    public StudioDialog(Stage owner) throws IOException {
        FXMLLoader loader = new FXMLLoader(getClass().getResource("StudioDialog.fxml"));
        loader.setController(this);
        Parent root = loader.load();

        stage = new Stage();
        stage.initOwner(owner); // Block the main stage
        stage.initModality(Modality.APPLICATION_MODAL);
        stage.setTitle("Studio Selection");
        stage.setScene(new Scene(root));

        btnHost.setOnAction(e -> onHostClick());
        btnJoin.setOnAction(e -> onJoinClick());

        // Don't allow closing without a choice
        stage.setOnCloseRequest(e -> e.consume());
    }

    @FXML
    void onHostClick() {
        selection = "HOST";
        stage.close();
    }

    @FXML
    void onJoinClick() {
        selection = "JOIN";
        stage.close();
    }

    /**
     * Shows the dialog and waits for a user selection.
     * @return "HOST" or "JOIN"
     */
    public String showAndWait() {
        stage.showAndWait();
        return selection;
    }
}