package dev.lectern.sync;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads and writes Minecraft's servers.dat file (NBT format).
 *
 * Used to add or update the Lectern server entry in the player's
 * multiplayer server list. Only modifies the Lectern-managed entry,
 * preserving all other servers the player has added.
 */
public class ServersDatWriter {

    /**
     * Update (or add) a server entry in servers.dat.
     * If an entry with the same name exists, updates its IP.
     * If not, appends a new entry.
     * Preserves all other entries.
     */
    public static void updateServer(File instanceDir, String serverName, String serverAddress) throws IOException {
        File serversDat = new File(instanceDir, "servers.dat");

        List<ServerEntry> entries = new ArrayList<ServerEntry>();

        // Read existing entries if file exists
        if (serversDat.exists()) {
            try {
                entries = readServersDat(serversDat);
            } catch (Exception e) {
                System.out.println("[Lectern] Could not parse existing servers.dat, will create new: " + e.getMessage());
                entries = new ArrayList<ServerEntry>();
            }
        }

        // Find existing entry by name and update, or add new
        boolean found = false;
        for (ServerEntry entry : entries) {
            if (entry.name.equals(serverName)) {
                entry.ip = serverAddress;
                found = true;
                break;
            }
        }

        if (!found) {
            entries.add(new ServerEntry(serverName, serverAddress, false));
        }

        // Write back
        writeServersDat(serversDat, entries);
    }

    static class ServerEntry {
        String name;
        String ip;
        boolean hidden;

        ServerEntry(String name, String ip, boolean hidden) {
            this.name = name;
            this.ip = ip;
            this.hidden = hidden;
        }
    }

    /**
     * Read server entries from a servers.dat NBT file.
     * This is a minimal NBT reader that only handles the structure of servers.dat.
     */
    private static List<ServerEntry> readServersDat(File file) throws IOException {
        List<ServerEntry> entries = new ArrayList<ServerEntry>();
        DataInputStream dis = null;
        try {
            dis = new DataInputStream(new FileInputStream(file));

            // Root TAG_Compound
            byte rootType = dis.readByte();
            if (rootType != 0x0a) throw new IOException("Expected TAG_Compound at root");
            readNbtString(dis); // root name (empty)

            // Read tags until TAG_End
            while (true) {
                byte tagType = dis.readByte();
                if (tagType == 0x00) break; // TAG_End

                String tagName = readNbtString(dis);

                if (tagType == 0x09 && tagName.equals("servers")) {
                    // TAG_List
                    byte listType = dis.readByte(); // should be TAG_Compound (0x0a)
                    int count = dis.readInt();

                    for (int i = 0; i < count; i++) {
                        String name = null;
                        String ip = null;
                        boolean hidden = false;

                        // Read compound entries
                        while (true) {
                            byte entryType = dis.readByte();
                            if (entryType == 0x00) break; // TAG_End

                            String entryName = readNbtString(dis);

                            if (entryType == 0x08) { // TAG_String
                                String value = readNbtString(dis);
                                if (entryName.equals("name")) name = value;
                                else if (entryName.equals("ip")) ip = value;
                            } else if (entryType == 0x01) { // TAG_Byte
                                byte value = dis.readByte();
                                if (entryName.equals("hidden")) hidden = value != 0;
                            } else {
                                // Skip other tag types
                                skipNbtValue(dis, entryType);
                            }
                        }

                        if (name != null && ip != null) {
                            entries.add(new ServerEntry(name, ip, hidden));
                        }
                    }
                } else {
                    skipNbtValue(dis, tagType);
                }
            }
        } finally {
            if (dis != null) dis.close();
        }
        return entries;
    }

    /**
     * Write server entries to servers.dat in NBT format.
     */
    private static void writeServersDat(File file, List<ServerEntry> entries) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(baos);

        // Root TAG_Compound
        dos.writeByte(0x0a);
        writeNbtString(dos, "");

        // TAG_List "servers" of TAG_Compound
        dos.writeByte(0x09);
        writeNbtString(dos, "servers");
        dos.writeByte(0x0a); // element type: TAG_Compound
        dos.writeInt(entries.size());

        for (ServerEntry entry : entries) {
            // TAG_String "name"
            dos.writeByte(0x08);
            writeNbtString(dos, "name");
            writeNbtString(dos, entry.name);

            // TAG_String "ip"
            dos.writeByte(0x08);
            writeNbtString(dos, "ip");
            writeNbtString(dos, entry.ip);

            // TAG_Byte "hidden"
            dos.writeByte(0x01);
            writeNbtString(dos, "hidden");
            dos.writeByte(entry.hidden ? 1 : 0);

            // TAG_End (close entry compound)
            dos.writeByte(0x00);
        }

        // TAG_End (close root compound)
        dos.writeByte(0x00);

        dos.flush();

        FileOutputStream fos = null;
        try {
            fos = new FileOutputStream(file);
            fos.write(baos.toByteArray());
        } finally {
            if (fos != null) fos.close();
        }
    }

    private static String readNbtString(DataInputStream dis) throws IOException {
        int length = dis.readUnsignedShort();
        byte[] bytes = new byte[length];
        dis.readFully(bytes);
        return new String(bytes, "UTF-8");
    }

    private static void writeNbtString(DataOutputStream dos, String s) throws IOException {
        byte[] bytes = s.getBytes("UTF-8");
        dos.writeShort(bytes.length);
        dos.write(bytes);
    }

    /**
     * Skip an NBT value of the given type (for tags we don't care about).
     */
    private static void skipNbtValue(DataInputStream dis, byte type) throws IOException {
        switch (type) {
            case 0x01: dis.readByte(); break;        // TAG_Byte
            case 0x02: dis.readShort(); break;       // TAG_Short
            case 0x03: dis.readInt(); break;          // TAG_Int
            case 0x04: dis.readLong(); break;         // TAG_Long
            case 0x05: dis.readFloat(); break;        // TAG_Float
            case 0x06: dis.readDouble(); break;       // TAG_Double
            case 0x07: {                              // TAG_Byte_Array
                int len = dis.readInt();
                dis.skipBytes(len);
                break;
            }
            case 0x08: readNbtString(dis); break;     // TAG_String
            case 0x09: {                              // TAG_List
                byte listType = dis.readByte();
                int count = dis.readInt();
                for (int i = 0; i < count; i++) {
                    skipNbtValue(dis, listType);
                }
                break;
            }
            case 0x0a: {                              // TAG_Compound
                while (true) {
                    byte t = dis.readByte();
                    if (t == 0x00) break;
                    readNbtString(dis); // tag name
                    skipNbtValue(dis, t);
                }
                break;
            }
            case 0x0b: {                              // TAG_Int_Array
                int len = dis.readInt();
                dis.skipBytes(len * 4);
                break;
            }
            case 0x0c: {                              // TAG_Long_Array
                int len = dis.readInt();
                dis.skipBytes(len * 8);
                break;
            }
        }
    }
}
