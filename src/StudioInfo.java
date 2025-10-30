// This is a simple data class to hold server info
public class StudioInfo {
    private String studioName;
    private String ipAddress;
    private int port;

    public StudioInfo(String studioName, String ipAddress, int port) {
        this.studioName = studioName;
        this.ipAddress = ipAddress;
        this.port = port;
    }

    public String getStudioName() {
        return studioName;
    }

    public String getIpAddress() {
        return ipAddress;
    }

    public int getPort() {
        return port;
    }

    // This is how it will appear in the ListView
    @Override
    public String toString() {
        return studioName + " (" + ipAddress + ")";
    }
}