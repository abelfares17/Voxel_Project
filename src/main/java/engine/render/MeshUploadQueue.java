package engine.render;
import java.util.concurrent.ConcurrentLinkedQueue;

public class MeshUploadQueue {
    private final ConcurrentLinkedQueue<MeshData> queue = new ConcurrentLinkedQueue<>();
    public void add(MeshData data) { queue.add(data); }
    public MeshData poll() { return queue.poll(); }
    public boolean isEmpty() { return queue.isEmpty(); }
}