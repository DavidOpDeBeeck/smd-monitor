package app.dodb.smd.monitor.hub.budget;

// The heap each kind of record the hub keeps takes, estimated as a constant per kind (M6): the record with what it alone refers to, and its
// slots in the structures that hold it. Names a record shares with others of its application are counted once, apart. The layout is that of a
// 64-bit JVM with compressed references, the default below a 32 GB heap.
public final class Footprint {

    private static final int HEADER = 12;
    private static final int REFERENCE = 4;
    private static final int LONG = 8;
    private static final int INT = 4;
    private static final long ARRAY_HEADER = 16;

    private static final long KEY = object(3 * LONG);
    private static final long HASH_ENTRY = object(INT + 3 * REFERENCE) + 2 * REFERENCE;
    private static final long QUEUE_SLOT = 2 * REFERENCE;
    private static final long INDEX_ENTRY = object(5 * REFERENCE + LONG + 3);
    private static final long FACT_RECORD = object(6 * REFERENCE);
    private static final long INVOCATION_ID = object(LONG);
    private static final long MESSAGE_ID = object(REFERENCE) + object(2 * LONG);
    private static final long INSTANT = object(LONG + INT);
    private static final long DURATION = object(LONG + INT);
    private static final long OPTIONAL = object(REFERENCE);
    private static final long LIST = object(2 * INT + REFERENCE) + ARRAY_HEADER + 10 * REFERENCE;
    private static final long SMALL_MAP = object(3 * INT + 4 * REFERENCE) + ARRAY_HEADER + 16 * REFERENCE + HASH_ENTRY + object(LONG);

    // The span index (J1): a send, with its key and its slots in the index and in its application's arrival queue; a span adds its fact, and
    // the key of its parent when it continues one.
    public static final long SEND = HASH_ENTRY + KEY + QUEUE_SLOT + INDEX_ENTRY;
    public static final long FACT = SEND + FACT_RECORD + INVOCATION_ID + MESSAGE_ID + INSTANT + DURATION;
    public static final long PARENT_KEY = KEY;
    // A child that waits for its parent, in the list of its parent's key.
    public static final long AWAITING = HASH_ENTRY + LIST;
    // The parent of children that left unlinked, remembered for another horizon with their count per application.
    public static final long TOMBSTONE = object(2 * REFERENCE + LONG) + HASH_ENTRY + QUEUE_SLOT + SMALL_MAP;

    // A retained invocation: the specimen with its slots in its chunk and in the chunk's copy while an epoch shares it, its slot in Recent,
    // its slots in its trace's members and snapshot as they grow, and its entry in the span index, which lives as long as it.
    public static final long SPECIMEN = object(7 * REFERENCE + 11 * LONG + 1) + 2 * (LONG + REFERENCE + INT) + QUEUE_SLOT + 3 * LONG + FACT + PARENT_KEY;
    // The summary of the parent of a retained invocation, once found, with the parent's message ID.
    public static final long PARENT = object(LONG + 5 * REFERENCE + 1) + 2 * INVOCATION_ID + MESSAGE_ID;
    // A trace: its component, which grows as members join, and its snapshot, with their slots.
    public static final long TRACE = object(5 * REFERENCE + 5 * INT + 3 * LONG + 4) + ARRAY_HEADER + LONG + ARRAY_HEADER + 4 * INT
        + object(3 * LONG + 3 * INT + 3 * REFERENCE) + ARRAY_HEADER + LONG + LIST + object(4 * REFERENCE + INT) + ARRAY_HEADER + 4 * REFERENCE
        + 2 * REFERENCE;

    // A count of one timed key in one second, bucket, or total, with its slot and up to two latency buckets inline; a histogram that uses more
    // buckets adds an array of them. An occurrence key counts in an int beside its key in the slots of its table.
    public static final long CELL = object(5 * INT + REFERENCE + 2 * LONG) + 2 * (INT + REFERENCE);
    public static final long HISTOGRAM = ints(19);
    public static final long OCCURRENCE = 2 * (INT + INT);
    // A second or 10-second bucket of counts, with its slot in the ordered map, and each table it holds cells of.
    public static final long BLOCK = object(REFERENCE) + ARRAY_HEADER + 10 * REFERENCE + object(5 * REFERENCE + 1) + object(LONG);
    public static final long BLOCK_TABLE = object(3 * REFERENCE + INT) + 2 * (ARRAY_HEADER + 8 * INT);
    // A key of the catalog: its record, its ID, and its slots.
    public static final long CATALOG_KEY = HASH_ENTRY + object(INT) + REFERENCE + object(3 * REFERENCE) + object(2 * REFERENCE) + string(48);
    // A name or stage an application's invocations share, other than a string, with its slot.
    private static final long NAME = HASH_ENTRY + 64;

    // A span waiting in an intake queue, as decoded: its references, IDs, stage, thread name, and outcome.
    public static final long QUEUED_SPAN = object(8 * REFERENCE) + 2 * (object(2 * REFERENCE) + string(32) + string(16)) + OPTIONAL + MESSAGE_ID
        + object(2 * REFERENCE) + object(2 * REFERENCE) + string(64) + OPTIONAL + INSTANT + string(48) + object(2 * REFERENCE) + DURATION + REFERENCE;

    private Footprint() {
    }

    // What an application's invocations share, such as a thread name or an exception type, counted one by one as their length varies.
    public static long name(Object name) {
        return name instanceof String string ? HASH_ENTRY + string(string.length()) : NAME;
    }

    // An array of ints, such as a series of counts per second.
    public static long ints(int length) {
        return (ARRAY_HEADER + (long) INT * length + 7) / 8 * 8;
    }

    // A string of Latin-1 characters, as the hub's names are.
    public static long string(int length) {
        return object(REFERENCE + INT + 2) + (ARRAY_HEADER + length + 7) / 8 * 8;
    }

    private static long object(int fields) {
        return (HEADER + fields + 7) / 8 * 8;
    }
}
