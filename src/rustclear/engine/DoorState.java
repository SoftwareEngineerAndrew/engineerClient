package net.rustclear.mod.engine;

/** A door during a run. */
public final class DoorState {
    public final Layout.Door door;
    /** A key for it has been dropped (only one ever is). */
    public boolean keyGranted;
    /** Opened with a key, or open from the start (entrance, the wither door into fairy). */
    public boolean opened;
    /** Can be clicked to open (wither and blood doors, until opened). */
    public boolean locked;

    public DoorState(Layout.Door door) {
        this.door = door;
    }

    public Layout.DoorType type() {
        return door.type();
    }

    /** Inside the door's 5x5x5 frame. */
    public boolean contains(int x, int y, int z) {
        return Math.abs(x - door.x()) <= 2 && Math.abs(z - door.z()) <= 2 && y >= 69 && y <= 73;
    }
}
