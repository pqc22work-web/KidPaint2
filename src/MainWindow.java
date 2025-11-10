import javafx.animation.AnimationTimer;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.image.PixelReader;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javafx.util.Duration;

import java.io.*;
import java.net.Socket;
import java.util.Arrays;
import java.util.LinkedList;

public class MainWindow {
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

    @FXML
    ChoiceBox<String> chbMode;
    Socket socket;
    DataInputStream in;
    DataOutputStream out;

    @FXML
    Button btnSend;
    @FXML
    TextField txtMsg;
    @FXML
    TextArea areaMsg;
    @FXML
    Canvas canvas;
    @FXML
    Pane container;
    @FXML
    Pane panePicker;
    @FXML
    Pane paneColor;
    @FXML
    Button btnSave;
    @FXML
    Button btnLoad;
    @FXML
    Button btnClear;
    @FXML
    ListView<String> userList;
    @FXML
    Label lblGameInfo;

    String username;
    int numPixels = 100; // <-- FIXED (was 10)
    Stage stage;
    AnimationTimer animationTimer;
    int[][] data;
    double pixelSize, padSize, startX, startY;
    int selectedColorARGB;
    boolean isPenMode = true;
    LinkedList<Point> filledPixels = new LinkedList<Point>();
    ObservableList<String> connectedUsers; // <-- ADDED
    private Timeline countdownTimer;
    private int timeRemaining;

    int lastRow = -1;
    int lastCol = -1;

    class Point {
        int x, y;

        Point(int x, int y) {
            this.x = x;
            this.y = y;
        }
    }

    public MainWindow(Stage stage, String username, String ip, int port) throws IOException {
        this.username = username;

        socket = new Socket(ip, port);
        in = new DataInputStream(socket.getInputStream());
        out = new DataOutputStream(socket.getOutputStream());

        out.write(NAME);
        out.writeInt(username.length());
        out.write(username.getBytes());
        out.flush();

        FXMLLoader loader = new FXMLLoader(getClass().getResource("mainWindownUI.fxml"));
        loader.setController(this);
        Parent root = loader.load();
        Scene scene = new Scene(root);

        this.stage = stage;
        stage.setScene(scene);
        stage.setMinWidth(scene.getWidth());
        stage.setMinHeight(scene.getHeight());

        canvas.widthProperty().bind(container.widthProperty());
        canvas.heightProperty().bind(container.heightProperty());
        canvas.widthProperty().addListener(w -> onCanvasSizeChange());
        canvas.heightProperty().addListener(h -> onCanvasSizeChange());

        btnSend.setOnAction(event -> {
            sendText(txtMsg.getText());
            txtMsg.clear();
        });

        stage.setOnCloseRequest(event -> quit());

        stage.show();
        initial();

        animationTimer.start();

        Thread thread = new Thread(this::receiveData);
        thread.start();
    }

    void sendText(String text) {
        text = text.trim();
        if (text.isEmpty()) {
            return;
        }

        try {
            if (text.startsWith("/w ")) {
                // Whisper command
                String[] parts = text.split(" ", 3);
                if (parts.length < 3) {
                    areaMsg.appendText("*** Invalid whisper format. Use: /w <username> <message> ***\n");
                    return;
                }
                String targetUser = parts[1];
                String whisperMsg = parts[2];

                out.write(WHISPER_MESSAGE);
                out.writeUTF(targetUser);
                out.writeUTF(whisperMsg);
            } else {
                // Public message
                out.write(MESSAGE);
                out.writeInt(text.length());
                out.write(text.getBytes());
            }
            out.flush();
        } catch (IOException ex) {
            System.out.println("Connection dropped!");
        }
    }

    void receiveData() {
        try {
            while (true) {
                int dataType = in.read();
                switch (dataType) {
                    case PIXELS:
                        receivePixels();
                        break;
                    case MESSAGE:
                        receiveMsg();
                        break;
                    case FULL_SKETCH:
                        receiveFullSketch();
                        break;
                    case FULL_SKETCH_UPDATE:
                        receiveFullSketch();
                        break;
                    case USER_JOINED: // <-- ADDED
                        receiveUserJoined();
                        break;
                    case FULL_USER_LIST: // <-- ADDED
                        receiveFullUserList();
                        break;
                    case USER_LEFT: // <-- ADDED
                        receiveUserLeft();
                        break;
                    case GAME_INFO:
                        receiveGameInfo();
                        break;
                    case GAME_OVER:
                        receiveGameOver();
                        break;
                }
            }
        } catch (IOException ex) {
            System.out.println("Disconnected! Bye!");
        }
    }

    void receiveMsg() throws IOException {
        int size = in.readInt();
        byte[] buffer = new byte[size];
        in.read(buffer, 0, size);
        String msg = new String(buffer, 0, size);
        areaMsg.appendText(msg + "\n");
    }

    void receivePixels() throws IOException {
        int color = in.readInt();
        int size = in.readInt();
        for (int i = 0; i < size; i++) {
            int x = in.readInt();
            int y = in.readInt();
            data[y][x] = color;
        }
    }

    void onCanvasSizeChange() {
        double w = canvas.getWidth();
        double h = canvas.getHeight();
        padSize = Math.min(w, h);
        startX = (w - padSize) / 2;
        startY = (h - padSize) / 2;
        pixelSize = padSize / numPixels;
    }

    void quit() {
        System.out.println("Bye bye");
        stage.close();
        System.exit(0);
    }

    void initial() throws IOException {
        data = new int[numPixels][numPixels];
        animationTimer = new AnimationTimer() {
            @Override
            public void handle(long l) {
                render();
            }
        };
        chbMode.setValue("Pen");

        canvas.setOnMousePressed(event -> {
            isPenMode = chbMode.getValue().equals("Pen");
            filledPixels.clear();
            if (isPenMode) penToData(event.getX(), event.getY());
        });
        canvas.setOnMouseDragged(event -> {
            if (isPenMode) penToData(event.getX(), event.getY());
        });
        canvas.setOnMouseReleased(event -> {
            if (!isPenMode) bucketToData(event.getX(), event.getY());
            try {
                sendPixelChanges();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            lastRow = -1;
            lastCol = -1;
        });

        btnSave.setOnAction(e -> saveSketch());
        btnLoad.setOnAction(e -> loadSketch());
        btnClear.setOnAction(e -> clearSketch());

        // --- ADDED ---
        connectedUsers = FXCollections.observableArrayList();
        userList.setItems(connectedUsers);
        // --- END ADD ---

        initColorMap();
    }

    void sendPixelChanges() throws IOException {
        out.write(PIXELS);
        out.writeInt(selectedColorARGB);
        out.writeInt(filledPixels.size());
        for (Point p : filledPixels) {
            out.writeInt(p.x);
            out.writeInt(p.y);
        }
        out.flush();
    }

    void initColorMap() throws IOException {
        Image image = new Image("file:color_map.png");
        ImageView imageView = new ImageView(image);
        imageView.setFitHeight(30.0);
        imageView.setPreserveRatio(true);
        panePicker.getChildren().add(imageView);

        double imageWidth = image.getWidth();
        double imageHeight = image.getHeight();
        double viewWidth = imageView.getBoundsInParent().getWidth();
        double viewHeight = imageView.getBoundsInParent().getHeight();
        double scaleX = imageWidth / viewWidth;
        double scaleY = imageHeight / viewHeight;

        pickColor(image, 0, 0, imageWidth, imageHeight);

        panePicker.setOnMouseClicked(event -> {
            double x = event.getX();
            double y = event.getY();
            int imgX = (int) (x * scaleX);
            int imgY = (int) (y * scaleY);
            pickColor(image, imgX, imgY, imageWidth, imageHeight);
        });
    }

    void pickColor(Image image, int imgX, int imgY, double imageWidth, double imageHeight) {
        if (imgX >= 0 && imgX < imageWidth && imgY >= 0 && imgY < imageHeight) {
            PixelReader reader = image.getPixelReader();
            selectedColorARGB = reader.getArgb(imgX, imgY);
            Color color = reader.getColor(imgX, imgY);
            paneColor.setStyle("-fx-background-color:#" + color.toString().substring(2));
        }
    }

    void penToData(double mx, double my) {
        if (mx > startX && mx < startX + padSize && my > startY && my < startY + padSize) {
            int row = (int) ((my - startY) / pixelSize);
            int col = (int) ((mx - startX) / pixelSize);
            if (row != lastRow || col != lastCol) {
                data[row][col] = selectedColorARGB;
                filledPixels.add(new Point(col, row));
                lastRow = row;
                lastCol = col;
            }
        }
    }

    void bucketToData(double mx, double my) {
        if (mx > startX && mx < startX + padSize && my > startY && my < startY + padSize) {
            int row = (int) ((my - startY) / pixelSize);
            int col = (int) ((mx - startX) / pixelSize);
            paintArea(col, row);
        }
    }

    public void paintArea(int col, int row) {
        int oriColor = data[row][col];
        LinkedList<Point> buffer = new LinkedList<Point>();
        if (oriColor != selectedColorARGB) {
            buffer.add(new Point(col, row));
            while (!buffer.isEmpty()) {
                Point p = buffer.removeFirst();
                col = p.x;
                row = p.y;
                if (data[row][col] != oriColor) continue;
                data[row][col] = selectedColorARGB;
                filledPixels.add(p);
                if (col > 0 && data[row][col - 1] == oriColor) buffer.add(new Point(col - 1, row));
                if (col < data[0].length - 1 && data[row][col + 1] == oriColor) buffer.add(new Point(col + 1, row));
                if (row > 0 && data[row - 1][col] == oriColor) buffer.add(new Point(col, row - 1));
                if (row < data.length - 1 && data[row + 1][col] == oriColor) buffer.add(new Point(col, row + 1));
            }
        }
    }

    Color fromARGB(int argb) {
        return Color.rgb((argb >> 16) & 0xFF, (argb >> 8) & 0xFF, argb & 0xFF, ((argb >> 24) & 0xFF) / 255.0);
    }

    void render() {
        GraphicsContext gc = canvas.getGraphicsContext2D();
        gc.clearRect(0, 0, canvas.getWidth(), canvas.getHeight());
        double x = startX;
        double y = startY;
        gc.setStroke(Color.GRAY);
        for (int row = 0; row < numPixels; row++) {
            for (int col = 0; col < numPixels; col++) {
                gc.setFill(fromARGB(data[row][col]));
                gc.fillOval(x, y, pixelSize, pixelSize);
                gc.strokeOval(x, y, pixelSize, pixelSize);
                x += pixelSize;
            }
            x = startX;
            y += pixelSize;
        }
    }

    // --- SAVE/LOAD/CLEAR/SKETCH METHODS ---

    void saveSketch() {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Save Sketch");
        fileChooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Sketch Data", "*.dat"));
        File file = fileChooser.showSaveDialog(stage);
        if (file != null) {
            try (DataOutputStream dos = new DataOutputStream(new FileOutputStream(file))) {
                dos.writeInt(numPixels);
                for (int row = 0; row < numPixels; row++) {
                    for (int col = 0; col < numPixels; col++) {
                        dos.writeInt(data[row][col]);
                    }
                }
            } catch (IOException ex) {
                System.out.println("Error saving sketch: " + ex.getMessage());
            }
        }
    }

    void loadSketch() {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Load Sketch");
        fileChooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Sketch Data", "*.dat"));
        File file = fileChooser.showOpenDialog(stage);
        if (file != null) {
            try (DataInputStream dis = new DataInputStream(new FileInputStream(file))) {
                int size = dis.readInt();
                if (size != numPixels) return;
                for (int row = 0; row < numPixels; row++) {
                    for (int col = 0; col < numPixels; col++) {
                        data[row][col] = dis.readInt();
                    }
                }
                sendFullSketchUpdate();
            } catch (IOException ex) {
                System.out.println("Error loading sketch: " + ex.getMessage());
            }
        }
    }

    void clearSketch() {
        for (int row = 0; row < numPixels; row++) {
            Arrays.fill(data[row], 0);
        }
        try {
            sendFullSketchUpdate();
        } catch (IOException ex) {
            System.out.println("Error sending clear sketch: " + ex.getMessage());
        }
    }

    void sendFullSketchUpdate() throws IOException {
        out.write(FULL_SKETCH_UPDATE);
        out.writeInt(numPixels);
        for (int row = 0; row < numPixels; row++) {
            for (int col = 0; col < numPixels; col++) {
                out.writeInt(data[row][col]);
            }
        }
        out.flush();
    }

    void receiveFullSketch() throws IOException {
        int size = in.readInt();
        if (size != this.numPixels) {
            this.numPixels = size;
            this.data = new int[size][size];
        }
        for (int row = 0; row < size; row++) {
            for (int col = 0; col < size; col++) {
                data[row][col] = in.readInt();
            }
        }
    }

    // --- USER LIST METHODS (ADDED) ---

    void receiveFullUserList() throws IOException {
        int count = in.readInt();
        System.out.println("Receiving full user list of " + count + " users.");
        Platform.runLater(() -> {
            connectedUsers.clear();
            try {
                for (int i = 0; i < count; i++) {
                    connectedUsers.add(in.readUTF());
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        });
    }

    void receiveUserJoined() throws IOException {
        String name = in.readUTF();
        System.out.println("User joined: " + name);
        Platform.runLater(() -> {
            connectedUsers.add(name);
            areaMsg.appendText("*** " + name + " has joined the studio. ***\n");
        });
    }

    void receiveUserLeft() throws IOException {
        String name = in.readUTF();
        System.out.println("User left: " + name);
        Platform.runLater(() -> {
            connectedUsers.remove(name);
            areaMsg.appendText("*** " + name + " has left the studio. ***\n");
        });
    }

    // --- GAME MODE METHODS (ADDED) ---

    void receiveGameInfo() throws IOException {
        String theme = in.readUTF();
        timeRemaining = in.readInt();
        Platform.runLater(() -> {
            updateGameInfoLabel(theme);
            startTimer(theme);
        });
    }

    void receiveGameOver() {
        Platform.runLater(() -> {
            if (countdownTimer != null) {
                countdownTimer.stop();
            }
            lblGameInfo.setText(lblGameInfo.getText() + " | TIME'S UP!");
            Alert alert = new Alert(Alert.AlertType.INFORMATION);
            alert.setTitle("Game Over");
            alert.setHeaderText("Time's Up!");
            alert.setContentText("The drawing time has ended.");
            alert.showAndWait();
        });
    }

    void startTimer(String theme) {
        if (countdownTimer != null) {
            countdownTimer.stop();
        }
        countdownTimer = new Timeline(new KeyFrame(Duration.seconds(1), e -> {
            timeRemaining--;
            updateGameInfoLabel(theme);
            if (timeRemaining <= 0) {
                countdownTimer.stop();
            }
        }));
        countdownTimer.setCycleCount(Timeline.INDEFINITE);
        countdownTimer.play();
    }

    void updateGameInfoLabel(String theme) {
        int minutes = timeRemaining / 60;
        int seconds = timeRemaining % 60;
        lblGameInfo.setText(String.format("Theme: %s | Time: %02d:%02d", theme, minutes, seconds));
    }
}