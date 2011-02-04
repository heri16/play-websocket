package play.data;

import java.util.concurrent.ConcurrentNavigableMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 *
 * @author heri16
 */
public class ChunkStream {
    
    private final CopyOnWriteArrayList<ChunkListener> listeners = new CopyOnWriteArrayList<ChunkListener>();

    private final ConcurrentNavigableMap<Long, byte[]> chunkBuffer = new ConcurrentSkipListMap<Long, byte[]>();
    
    public void addListener(ChunkListener listener) {
        listener.setChunkBuffer(chunkBuffer);
        this.listeners.add(listener);
    }
    
    public void removeListener(ChunkListener listener) {
        this.listeners.remove(listener);
    }
    
    public void addChunk(byte[] ba) {
        long key = putChunk(ba);
        for(ChunkListener listener : this.listeners) {
            listener.onOneNewChunk(key, ba);
        }
    }
    
    public long putChunk(byte[] ba) {
        long key = System.currentTimeMillis();
        chunkBuffer.put(key, ba);
        return key;
    }
    
    public void notifyManyNewChunks() {
        for(ChunkListener listener : this.listeners) {
            listener.onManyNewChunks();
        }
    }

    public ConcurrentNavigableMap<Long, byte[]> getChunkBuffer() {
        return chunkBuffer;
    }
    
    public static interface ChunkListener {
        void setChunkBuffer(ConcurrentNavigableMap<Long, byte[]> chunkBuffer);
        void onOneNewChunk(Long key, byte[] chunk);
        void onManyNewChunks();
    }
    
    public static abstract class SimpleChunkListener implements ChunkListener {

        protected ConcurrentNavigableMap<Long, byte[]> chunkBuffer;
        protected long currentKeyIndex;
        
        public void setChunkBuffer(ConcurrentNavigableMap<Long, byte[]> chunkBuffer) {
            this.chunkBuffer = chunkBuffer;
        }

        public void onOneNewChunk(Long key, byte[] chunk) {
            currentKeyIndex = key;
            processChunk(chunk);
        }
        
        
        public void onManyNewChunks() {
            currentKeyIndex = chunkBuffer.lastKey();
            for (byte[] chunk : chunkBuffer.headMap(currentKeyIndex).values()) {
                processChunk(chunk);
            }
        }

        protected abstract void processChunk(byte[] chunk);
    }

}
