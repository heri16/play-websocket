package play.server;

import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.buffer.ChannelBuffers;
import org.jboss.netty.channel.*;
import org.jboss.netty.handler.codec.http.*;
import org.jboss.netty.handler.codec.http.websocket.*;

import static org.jboss.netty.handler.codec.http.HttpHeaders.Names.*;

import play.server.PlayHandler;
import play.Invoker;
import play.Logger;
import play.Play;
import play.PlayPlugin;
import play.mvc.Http;
import play.mvc.Http.Request;
import play.mvc.Http.Response;
import play.data.ChunkStream;

import java.io.*;
import java.nio.ByteBuffer;
import java.util.logging.Level;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.security.MessageDigest;
import java.util.concurrent.ConcurrentNavigableMap;

/**
 *
 * @author heri16
 */
public class PlayWebsocketHandler extends PlayHandler {

    // Specify ws:// or wss:// here
    private static final String WEBSOCKET_SCHEME = "ws://";
    
    // Specify PlayRouter method prefix here
    private static final String WEBSOCKET_METHOD_NAME = "WEBSOCKET";
    
    /* A map that store the websocket stream.
     * ConcurrentMap provides good lookup performance.
     * ChannelHandlerContext can be used as reference, as it does not change on every messageReceived.
     * Refer to http://docs.jboss.org/netty/3.1/api/org/jboss/netty/channel/ChannelHandlerContext.html#setAttachment
     * Alternatively, client ip address may be used as key.
     */
    private final ConcurrentMap<ChannelHandlerContext, ChunkStream> activeStreamMap = new ConcurrentSkipListMap<ChannelHandlerContext, ChunkStream>();
    
    @Override
    public void messageReceived(ChannelHandlerContext ctx, MessageEvent e) throws Exception {
        Logger.trace("messageReceived: begin");
        
        final Object msg = e.getMessage();
        if (msg instanceof WebSocketFrame) {
            final WebSocketFrame nettyWebSocketFrame = (WebSocketFrame) msg;
            
            // InboundStream is passed to playContoller to add arbitrary listeners (user-defined)
            // Data frames/chunks are pushed into the inboundStream to propagate to all listeners
            //FrameStream inboundStream = activeStreamMap.get(ctx);
            // Faster version of the above 
            ChunkStream inboundStream = (ctx.getAttachment() instanceof ChunkStream) ? (ChunkStream) ctx.getAttachment() : null;
            
            inboundStream.addChunk(nettyWebSocketFrame.getBinaryData().array());
        
        } else if (msg instanceof HttpRequest) {
            final HttpRequest nettyRequest = (HttpRequest) msg;

            if (HttpHeaders.Values.UPGRADE.equalsIgnoreCase(nettyRequest.getHeader(HttpHeaders.Names.CONNECTION)) &&
                HttpHeaders.Values.WEBSOCKET.equalsIgnoreCase(nettyRequest.getHeader(HttpHeaders.Names.UPGRADE))) {

                // Do upgrade handshake.
                handleWebSocketHandshakeRequest(ctx, nettyRequest);
                
                ChunkStream inboundStream = new ChunkStream();
                //activeStreamMap.put(ctx, inboundStream);
                // Faster version of the above
                ctx.setAttachment(inboundStream);
                
                ChunkStream outboundStream = new ChunkStream();
                outboundStream.addListener(getOutboundStreamListener(ctx));
                
                // Invoke PlayAction here
                try {
                    // Decided to use GET here instead as conforms to Websocket Protocol Specifications
                    //nettyRequest.setHeader("X-HTTP-Method-Override", WEBSOCKET_METHOD_NAME);
                    Request request = parseRequest(ctx, nettyRequest);
                    request = processRequest(request);
                    
                    //request.method = WEBSOCKET_METHOD_NAME;
                    request.args.put("inboundStream", inboundStream);
                    request.args.put("outboundStream", outboundStream);

                    final Response response = new Response();

                    Http.Response.current.set(response);
                    response.out = new ByteArrayOutputStream();
                    boolean raw = false;
                    for (PlayPlugin plugin : Play.plugins) {
                        if (plugin.rawInvocation(request, response)) {
                            raw = true;
                            break;
                        }
                    }
                    if (raw) {
                        copyResponse(ctx, request, response, nettyRequest);
                    } else {
                        Invoker.invoke(new NettyInvocation(request, response, ctx, nettyRequest, e));
                    }

                } catch (Exception ex) {
                    // serve500(ex, ctx, nettyRequest);
                    ctx.getChannel().write(new DefaultWebSocketFrame("Error 500: " + e.getMessage()));
                    ctx.getChannel().close();
                }
            } else {
                // Not a websocket frame nor upgrade request, pass to PlayHandler.
                super.messageReceived(ctx, e);
            }
        }

        Logger.trace("messageReceived: end");
    }
    
    @Override
    public void copyResponse(ChannelHandlerContext ctx, Request request, Response response, HttpRequest nettyRequest) throws Exception {
        Logger.trace("copyResponse: begin");
        
        final Object obj = response.direct;
        byte[] frameChunk = null;
        if (obj instanceof byte[]) {
            frameChunk = (byte[]) obj;
        }

        final boolean keepAlive = isKeepAlive(nettyRequest);
        if (frameChunk != null) {
            ChannelBuffer cb = ChannelBuffers.wrappedBuffer(frameChunk);
            ctx.getChannel().write(new DefaultWebSocketFrame(0, cb));
        } else {
            super.copyResponse(ctx, request, response, nettyRequest);
        }
        Logger.trace("copyResponse: end");
    }
    
    /*
     * Returns the websocket endpoint's Uniform Resource Identifier (URI)
     */
    private static String getWebSocketURI(HttpRequest nettyRequest) {
        return WEBSOCKET_SCHEME + nettyRequest.getHeader(HttpHeaders.Names.HOST) + nettyRequest.getUri();
    }
    
    /*
     * This method is an almost exact copy of Netty's sample-code for Websocket.
     * This is a proven implementation. You do not need to look into it.
     */
    private static void handleWebSocketHandshakeRequest(ChannelHandlerContext ctx, HttpRequest nettyRequest) {
        // Create the WebSocket handshake response.
        HttpResponse res = new DefaultHttpResponse(
                HttpVersion.HTTP_1_1,
                new HttpResponseStatus(101, "Web Socket Protocol Handshake"));
        res.addHeader(HttpHeaders.Names.UPGRADE, HttpHeaders.Values.WEBSOCKET);
        res.addHeader(HttpHeaders.Names.CONNECTION, HttpHeaders.Values.UPGRADE);

        // Fill in the headers and contents depending on handshake method.
        if (nettyRequest.containsHeader(SEC_WEBSOCKET_KEY1) &&
            nettyRequest.containsHeader(SEC_WEBSOCKET_KEY2)) {
            // New handshake method with a challenge:
            res.addHeader(SEC_WEBSOCKET_ORIGIN, nettyRequest.getHeader(ORIGIN));
            res.addHeader(SEC_WEBSOCKET_LOCATION, getWebSocketURI(nettyRequest));
            String protocol = nettyRequest.getHeader(SEC_WEBSOCKET_PROTOCOL);
            if (protocol != null) {
                res.addHeader(SEC_WEBSOCKET_PROTOCOL, protocol);
            }

            // Calculate the answer of the challenge.
            String key1 = nettyRequest.getHeader(SEC_WEBSOCKET_KEY1);
            String key2 = nettyRequest.getHeader(SEC_WEBSOCKET_KEY2);
            int a = (int) (Long.parseLong(key1.replaceAll("[^0-9]", "")) / key1.replaceAll("[^ ]", "").length());
            int b = (int) (Long.parseLong(key2.replaceAll("[^0-9]", "")) / key2.replaceAll("[^ ]", "").length());
            long c = nettyRequest.getContent().readLong();
            ChannelBuffer input = ChannelBuffers.buffer(16);
            input.writeInt(a);
            input.writeInt(b);
            input.writeLong(c);
            byte[] digest = null;
            try {
                digest = MessageDigest.getInstance("MD5").digest(input.array());
            } catch (Exception ex) {
                java.util.logging.Logger.getLogger(PlayHandler.class.getName()).log(Level.SEVERE, null, ex);
            }
            ChannelBuffer output = ChannelBuffers.wrappedBuffer(digest);
            res.setContent(output);
        } else {
            // Old handshake method with no challenge:
            res.addHeader(WEBSOCKET_ORIGIN, nettyRequest.getHeader(ORIGIN));
            res.addHeader(WEBSOCKET_LOCATION, getWebSocketURI(nettyRequest));
            String protocol = nettyRequest.getHeader(WEBSOCKET_PROTOCOL);
            if (protocol != null) {
                res.addHeader(WEBSOCKET_PROTOCOL, protocol);
            }
        }

        // Upgrade the connection and send the handshake response.
        ChannelPipeline p = ctx.getChannel().getPipeline();
        p.remove("aggregator");
        p.replace("decoder", "wsdecoder", new WebSocketFrameDecoder());

        ctx.getChannel().write(res);

        p.replace("encoder", "wsencoder", new WebSocketFrameEncoder());
    }
    
    /*
     * Makes it easy to override (via inheritance) for desired class of listener
     */
    protected ChunkStream.ChunkListener getOutboundStreamListener(ChannelHandlerContext ctx) {
        // Static methods give much better performance in jvm
        return (new OutboundStreamWriter(ctx));
    }
    
    /*
     * Basic ChunkListener that writes out Websocket Frames
     */
    private static class OutboundStreamWriter implements ChunkStream.ChunkListener {

        private final ChannelHandlerContext ctx;
        
        private ConcurrentNavigableMap<Long, byte[]> chunkBuffer;
        private long currentKeyIndex;

        public OutboundStreamWriter(ChannelHandlerContext ctx) {
            this.ctx = ctx;
        }
        
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

        private void processChunk(byte[] chunk) {
            ChannelBuffer cb = ChannelBuffers.wrappedBuffer(chunk);
            ctx.getChannel().write(new DefaultWebSocketFrame(0, cb));
        }

    }

}
