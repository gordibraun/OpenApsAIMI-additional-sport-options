package app.aaps.rfcommpeer;

final class PeerPolicy {
    static final String WATCH = "7C:F0:E5:5F:5D:52";
    static final String PHONE = "34:2D:0D:34:ED:C2";
    static final String UUID = "b19a7562-fc1b-4bc4-a4c1-e83e66b3343e";
    static final String SERIAL_UUID = "00001101-0000-1000-8000-00805f9b34fb";

    static String peer(String model) {
        if ("OPWWE251".equals(model)) return PHONE;
        if ("SM-G955F".equals(model)) return WATCH;
        throw new IllegalArgumentException("Not an approved peer device");
    }

    static void validate(String model, String address, String id, int delayMs) {
        if (!peer(model).equalsIgnoreCase(address)) throw new IllegalArgumentException("Peer mismatch");
        if (id == null || !id.matches("[A-Za-z0-9_-]{1,64}")) throw new IllegalArgumentException("Invalid id");
        if (delayMs < 0 || delayMs > 180000) throw new IllegalArgumentException("Invalid delay");
    }
}
