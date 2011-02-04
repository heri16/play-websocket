package controllers.websocket;

import play.mvc.*;
import play.data.ChunkStream;
import play.mvc.results.NoResult;

public class EchoTest extends Controller {

    public static void index() {
        render();
    }

    public static void echomsg(ChunkStream inboundStream, ChunkStream outboundStream) {
        
        if (inboundStream != null && outboundStream != null) {
            inboundStream.addListener(new EchoChunkListener(outboundStream));
        } else {
            response.direct = "WARNING: Cannot bind ChunkStream in Controller".getBytes();
        }
        
        response.direct = "OK".getBytes();
    }
    
    public static void echomsg2() {
        Object os = request.args.get("outboundStream");
        ChunkStream outboundStream = (os instanceof ChunkStream) ? (ChunkStream) os : null;
        //outboundStream.addChunk("hahhaha".getBytes());
        
        Object is = request.args.get("inboundStream");
        ChunkStream inboundStream = (is instanceof ChunkStream) ? (ChunkStream) is : null;
        inboundStream.addListener(new EchoChunkListener(outboundStream));
        
        response.direct = "OK".getBytes();
        throw new NoResult();
    }
    
    /*
     * Basic ChunkListener that echos Websocket Chunks/Frames
     */
    private static class EchoChunkListener extends ChunkStream.SimpleChunkListener {

        private ChunkStream outboundStream;
        
        public EchoChunkListener(ChunkStream outboundStream) {
            this.outboundStream = outboundStream;
        }
        
        @Override
        protected void processChunk(byte[] chunk) {
            String inString = new String(chunk);
            inString = inString.toUpperCase();
            outboundStream.addChunk(inString.getBytes());
        }

    }
    
}