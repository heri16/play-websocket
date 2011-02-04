import java.util.Map;
import java.lang.annotation.Annotation;

import play.PlayPlugin;
import play.mvc.Http.Request;
import play.data.ChunkStream;

/*
 * Play plugin to add Websocket functionality
 */
public class WebsocketPlugin extends PlayPlugin {
    
    @Override
    @SuppressWarnings("unchecked")
    public Object bind(String name, Class clazz, java.lang.reflect.Type type, Annotation[] annotations, Map<String, String[]> params) {
        // TODO implement annotations
        if (ChunkStream.class.isAssignableFrom(clazz)) {
            ChunkStream chunkStream = (ChunkStream)Request.current().args.get(name);
            return chunkStream;
        }
        return super.bind(name, clazz, type, annotations, params);
    }

    @Override
    public Object bind(String name, Object o, Map<String, String[]> params) {
        if (o instanceof ChunkStream) {
            ChunkStream chunkStream = (ChunkStream)Request.current().args.get(name);
            return chunkStream;
        }
        return null;
    }
}
