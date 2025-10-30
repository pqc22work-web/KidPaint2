import javafx.animation.AnimationTimer;
import javafx.fxml.FXMLLoader;
import javafx.fxml.FXML;

import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Button;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.image.PixelReader;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import javafx.stage.FileChooser;
import javafx.scene.Parent;

import java.io.File;
import java.io.FileInputStream; // <-- ADD THIS IMPORT
import java.io.FileOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.sql.SQLOutput;
import java.util.Arrays;
import java.util.LinkedList;

public class MainWindow {
    final int NAME = 0;
    final int PIXELS = 1;
    final int MESSAGE = 2;
    final int FULL_SKETCH = 3;
    final int FULL_SKETCH_UPDATE = 4;
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

    String username;
    int numPixels = 10;
    Stage stage;
    AnimationTimer animationTimer;
    int[][] data;
    double pixelSize, padSize, startX, startY;
    int selectedColorARGB;
    boolean isPenMode = true;
    LinkedList<Point> filledPixels = new LinkedList<Point>();

    int lastRow = -1;
    int lastCol = -1;

    class Point{
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

        out.write(NAME);                   //indicate that I am sending a name
        out.writeInt(username.length()); //send the length of username
        out.write(username.getBytes()); // convert username into bytes, and send the bytes out
        out.flush();                    //ensure everything sent


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
        canvas.widthProperty().addListener(w->onCanvasSizeChange());
        canvas.heightProperty().addListener(h->onCanvasSizeChange());

        btnSend.setOnAction(event -> {
            sendText(txtMsg.getText());
            txtMsg.clear();
        });

        stage.setOnCloseRequest(event -> quit());

        stage.show();
        initial();

        animationTimer.start();

        Thread thread = new Thread(()->{
            receiveData();
        });
        thread.start();
    }

    void sendText(String text) {
        try {
            out.write(MESSAGE);
            System.out.println(MESSAGE);

            out.writeInt(text.length());
            System.out.println(text.length());

            out.write(text.getBytes());
            System.out.println(text);

            out.flush();
        } catch (IOException ex) {
            System.out.println("Oh! My connection is dropped!");
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
                    // ADD THIS CASE
                    case FULL_SKETCH_UPDATE:
                        System.out.println("Receiving full sketch update from server...");
                        receiveFullSketch(); // We can reuse this method!
                        break;
                }
            }
        }catch (IOException ex){
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

    void receivePixels() throws IOException{
        int color = in.readInt();
        int size = in.readInt();
        for(int i=0; i<size; i++){
            int x = in.readInt();
            int y = in.readInt();

            data[y][x] = color;
        }
    }

    /**
     * Update canvas info when the window is resized
     */
    void onCanvasSizeChange() {
        double w = canvas.getWidth();
        double h = canvas.getHeight();
        padSize = Math.min(w, h);
        startX = (w - padSize)/2;
        startY = (h - padSize)/2;
        pixelSize = padSize / numPixels;
    }

    /**
     * terminate this program
     */
    void quit() {
        System.out.println("Bye bye");
        stage.close();
        System.exit(0);
    }

    /**
     * Initialize UI components
     * @throws IOException
     */
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
            if (isPenMode)
                penToData(event.getX(), event.getY());
        });

        canvas.setOnMouseDragged(event -> {
            if (isPenMode)
                penToData(event.getX(), event.getY());
        });

        canvas.setOnMouseReleased(event->{
            if (!isPenMode)
                bucketToData(event.getX(), event.getY());

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
        initColorMap();
    }

    /**
     * Opens a FileChooser to save the sketch data to a file.
     */
    void saveSketch() {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Save Sketch");
        fileChooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Sketch Data", "*.dat"));
        File file = fileChooser.showSaveDialog(stage);

        if (file != null) {
            try (DataOutputStream dos = new DataOutputStream(new FileOutputStream(file))) {
                dos.writeInt(numPixels); // Write dimension
                for (int row = 0; row < numPixels; row++) {
                    for (int col = 0; col < numPixels; col++) {
                        dos.writeInt(data[row][col]);
                    }
                }
                System.out.println("Sketch saved to: " + file.getPath());
            } catch (IOException ex) {
                System.out.println("Error saving sketch: " + ex.getMessage());
            }
        }
    }

    /**
     * Opens a FileChooser to load sketch data, updates local canvas,
     * and sends the new sketch to the server.
     */
    void loadSketch() {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Load Sketch");
        fileChooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Sketch Data", "*.dat"));
        File file = fileChooser.showOpenDialog(stage);

        if (file != null) {
            try (DataInputStream dis = new DataInputStream(new FileInputStream(file))) {
                int size = dis.readInt();
                if (size != numPixels) {
                    System.out.println("File has different dimensions. Skipping load.");
                    // Optionally, you could handle resizing here
                    return;
                }

                // Read data into local array
                for (int row = 0; row < numPixels; row++) {
                    for (int col = 0; col < numPixels; col++) {
                        data[row][col] = dis.readInt();
                    }
                }
                System.out.println("Sketch loaded from: " + file.getPath());

                // Now, send this full sketch to the server
                sendFullSketchUpdate();

            } catch (IOException ex) {
                System.out.println("Error loading sketch: " + ex.getMessage());
            }
        }
    }

    /**
     * Clears the entire sketch by setting all pixels to 0 (black)
     * and sends the update to the server.
     */
    void clearSketch() {
        System.out.println("Clearing sketch...");

        // Fill the data array with 0 (black)
        for (int row = 0; row < numPixels; row++) {
            // Arrays.fill is a fast way to set all columns in this row to 0
            Arrays.fill(data[row], 0);
        }

        // Send this "cleared" sketch to the server
        try {
            sendFullSketchUpdate();
        } catch (IOException ex) {
            System.out.println("Error sending clear sketch: " + ex.getMessage());
        }
    }

    /**
     * Sends the entire current 'data' array to the server.
     */
    void sendFullSketchUpdate() throws IOException {
        System.out.println("Sending full sketch update to server...");
        out.write(FULL_SKETCH_UPDATE);
        out.writeInt(numPixels);

        for (int row = 0; row < numPixels; row++) {
            for (int col = 0; col < numPixels; col++) {
                out.writeInt(data[row][col]);
            }
        }
        out.flush();
        System.out.println("Full sketch update sent.");
    }

    void sendPixelChanges() throws IOException {
        out.write(PIXELS);                  // message type
        out.writeInt(selectedColorARGB);    //send color
        out.writeInt(filledPixels.size());  //send how many pixels are modified

        System.out.println("# of pixels sent: " + filledPixels.size());
        for(Point p: filledPixels){         //send pixel positions one-by-one
            out.writeInt(p.x);
            out.writeInt(p.y);
        }
        out.flush();
    }

    /**
     * Initialize color map
     * @throws IOException
     */
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

            int imgX = (int)(x * scaleX);
            int imgY = (int)(y * scaleY);

            pickColor(image, imgX, imgY, imageWidth, imageHeight);
        });
    }

    /**
     * Pick a color from the color map image
     * @param image color map image
     * @param imgX x position in the image
     * @param imgY y position in the image
     * @param imageWidth the width of the image
     * @param imageHeight the height of the image
     */
    void pickColor(Image image, int imgX, int imgY, double imageWidth, double imageHeight) {
        if (imgX >= 0 && imgX < imageWidth && imgY >= 0 && imgY < imageHeight) {
            PixelReader reader = image.getPixelReader();

            selectedColorARGB = reader.getArgb(imgX, imgY);

            Color color = reader.getColor(imgX, imgY);
            paneColor.setStyle("-fx-background-color:#" + color.toString().substring(2));
        }
    }

    /**
     * Invoked when the Pen mode is used. Update sketch data array and store updated pixels in a list named filledPixels
     * @param mx mouse down/drag position x
     * @param my mouse down/drag position y
     */
    void penToData(double mx, double my) {
        if (mx > startX && mx < startX + padSize && my > startY && my < startY + padSize) {
            int row = (int) ((my - startY) / pixelSize);
            int col = (int) ((mx - startX) / pixelSize);

            if(row != lastRow || col != lastCol) {
                data[row][col] = selectedColorARGB;
                filledPixels.add(new Point(col, row));
                lastRow = row;
                lastCol = col;
            }
        }
    }

    /**
     * Invoked when the Bucket mode is used. It calls paintArea() to update sketch data array and store updated pixels in a list named filledPixels
     * @param mx mouse down/drag position x
     * @param my mouse down/drag position y
     */
    void bucketToData(double mx, double my) {
        if (mx > startX && mx < startX + padSize && my > startY && my < startY + padSize) {
            int row = (int) ((my - startY) / pixelSize);
            int col = (int) ((mx - startX) / pixelSize);
            paintArea(col, row);
        }
    }

    /**
     * Update the color of specific area
     * @param col position of the sketch data array
     * @param row position of the sketch data array
     */
    public void paintArea(int col, int row) {
        int oriColor = data[row][col];
        LinkedList<Point> buffer = new LinkedList<Point>();

        if (oriColor != selectedColorARGB) {
            buffer.add(new Point(col, row));

            while(!buffer.isEmpty()) {
                Point p = buffer.removeFirst();
                col = p.x;
                row = p.y;

                if (data[row][col] != oriColor) continue;

                data[row][col] = selectedColorARGB;
                filledPixels.add(p);

                if (col > 0 && data[row][col-1] == oriColor) buffer.add(new Point(col-1, row));
                if (col < data[0].length - 1 && data[row][col+1] == oriColor) buffer.add(new Point(col+1, row));
                if (row > 0 && data[row-1][col] == oriColor) buffer.add(new Point(col, row-1));
                if (row < data.length - 1 && data[row+1][col] == oriColor) buffer.add(new Point(col, row+1));
            }
        }
    }

    /**
     * Convert argb value from int format to JavaFX Color
     * @param argb
     * @return Color
     */
    Color fromARGB(int argb) {
        return Color.rgb(
                (argb >> 16) & 0xFF,
                (argb >> 8) & 0xFF,
                argb & 0xFF,
                ((argb >> 24) & 0xFF) / 255.0
        );
    }


    /**
     * Render the sketch data to the canvas
     */
    void render() {
        GraphicsContext gc = canvas.getGraphicsContext2D();
        gc.clearRect(0, 0, canvas.getWidth(), canvas.getHeight());

        double x = startX;
        double y = startY;

        gc.setStroke(Color.GRAY);

        // Corrected loop: iterate row by row (y-axis), then col by col (x-axis)
        for (int row = 0; row < numPixels; row++) {
            for (int col = 0; col < numPixels; col++) {

                // Access data as [row][col]
                gc.setFill(fromARGB(data[row][col]));

                gc.fillOval(x, y, pixelSize, pixelSize);
                gc.strokeOval(x, y, pixelSize, pixelSize);

                x += pixelSize; // Move right
            }
            x = startX; // Reset to left edge
            y += pixelSize; // Move down to next row
        }
    }

    void receiveFullSketch() throws IOException {
        int size = in.readInt(); // Read dimension (should be 100)

        if (size != this.numPixels) {
            System.out.println("Server grid size is " + size + ". Adjusting client.");
            this.numPixels = size;
            this.data = new int[size][size];
        }

        // Read the color data for every pixel
        for (int row = 0; row < size; row++) {
            for (int col = 0; col < size; col++) {
                data[row][col] = in.readInt();
            }
        }
        System.out.println("Full sketch received.");
    }
}
