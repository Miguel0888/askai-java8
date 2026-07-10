package io.github.ollama4j;

import io.github.ollama4j.exceptions.OllamaException;
import io.github.ollama4j.http.OllamaHttpClient;
import io.github.ollama4j.http.OllamaHttpResponse;
import io.github.ollama4j.http.OllamaLineListener;
import io.github.ollama4j.json.OllamaJson;
import io.github.ollama4j.models.ChatCompletion;
import io.github.ollama4j.models.ChatMessage;
import io.github.ollama4j.models.ChatTokenListener;
import io.github.ollama4j.models.EmbeddingResult;
import io.github.ollama4j.models.Model;
import io.github.ollama4j.models.ModelDetails;
import io.github.ollama4j.models.ModelInfo;
import io.github.ollama4j.models.PullProgress;
import io.github.ollama4j.models.PullProgressListener;
import io.github.ollama4j.models.RunningModel;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class Ollama {

    private final OllamaHttpClient httpClient;

    public Ollama() {
        this("http://127.0.0.1:11434");
    }

    public Ollama(String baseUrl) {
        this.httpClient = new OllamaHttpClient(baseUrl);
    }

    public void setRequestTimeoutSeconds(long seconds) {
        httpClient.setRequestTimeoutSeconds(seconds);
    }

    public boolean ping() throws OllamaException {
        return httpClient.get("/api/tags").isSuccessful();
    }

    public String getVersion() throws OllamaException {
        Map map = object(httpClient.get("/api/version"));
        return string(map, "version");
    }

    public List<Model> listModels() throws OllamaException {
        Map map = object(httpClient.get("/api/tags"));
        List result = list(map.get("models"));
        List<Model> models = new ArrayList<Model>();
        for (int i = 0; i < result.size(); i++) {
            Object item = result.get(i);
            if (item instanceof Map) {
                models.add(toModel((Map) item));
            }
        }
        return models;
    }

    public List<RunningModel> ps() throws OllamaException {
        Map map = object(httpClient.get("/api/ps"));
        List result = list(map.get("models"));
        List<RunningModel> models = new ArrayList<RunningModel>();
        for (int i = 0; i < result.size(); i++) {
            Object item = result.get(i);
            if (item instanceof Map) {
                Map itemMap = (Map) item;
                models.add(new RunningModel(
                        string(itemMap, "name"),
                        number(itemMap, "size"),
                        number(itemMap, "size_vram"),
                        string(itemMap, "expires_at")));
            }
        }
        return models;
    }

    public ModelInfo getModelDetails(String modelName) throws OllamaException {
        Map body = new LinkedHashMap();
        body.put("model", modelName);
        Map map = object(httpClient.post("/api/show", OllamaJson.toJson(body)));
        return new ModelInfo(
                string(map, "modelfile"),
                string(map, "parameters"),
                string(map, "template"),
                details(map.get("details")),
                stringList(map.get("capabilities")));
    }

    /** Map a JSON array of strings to a List; anything else yields an empty list. */
    private static List<String> stringList(Object value) {
        List<String> result = new ArrayList<String>();
        if (value instanceof List) {
            List values = (List) value;
            for (int i = 0; i < values.size(); i++) {
                Object entry = values.get(i);
                if (entry != null) {
                    result.add(String.valueOf(entry));
                }
            }
        }
        return result;
    }

    public void deleteModel(String modelName) throws OllamaException {
        Map body = new LinkedHashMap();
        body.put("name", modelName);
        require(httpClient.delete("/api/delete", OllamaJson.toJson(body)));
    }

    public void unloadModel(String modelName) throws OllamaException {
        Map body = new LinkedHashMap();
        body.put("model", modelName);
        body.put("prompt", "");
        body.put("stream", Boolean.FALSE);
        body.put("keep_alive", Integer.valueOf(0));
        require(httpClient.post("/api/generate", OllamaJson.toJson(body)));
    }

    public String generate(String modelName, String text) throws OllamaException {
        Map body = new LinkedHashMap();
        body.put("model", modelName);
        body.put("prompt", text == null ? "" : text);
        body.put("stream", Boolean.FALSE);
        Map map = object(httpClient.post("/api/generate", OllamaJson.toJson(body)));
        return string(map, "response");
    }

    public EmbeddingResult embed(String modelName, List<String> inputs) throws OllamaException {
        Map body = new LinkedHashMap();
        body.put("model", modelName);
        body.put("input", inputs == null ? new ArrayList<String>() : inputs);
        Map map = object(httpClient.post("/api/embed", OllamaJson.toJson(body)));
        List embeddings = list(map.get("embeddings"));
        List<List<Double>> vectors = new ArrayList<List<Double>>();
        for (int i = 0; i < embeddings.size(); i++) {
            vectors.add(doubleList(list(embeddings.get(i))));
        }
        return new EmbeddingResult(vectors);
    }

    public ChatCompletion streamChat(String modelName, List<ChatMessage> messages, String keepAlive,
                                     final ChatTokenListener listener) throws OllamaException {
        Map body = new LinkedHashMap();
        body.put("model", modelName);
        body.put("messages", toMessageMaps(messages));
        body.put("stream", Boolean.TRUE);
        if (keepAlive != null && keepAlive.trim().length() > 0) {
            body.put("keep_alive", keepAlive.trim());
        }
        final ChatCompletion[] completion = new ChatCompletion[] { new ChatCompletion("", 0L, 0L) };
        httpClient.postLines("/api/chat", OllamaJson.toJson(body), new OllamaLineListener() {
            public void onLine(String line) {
                Map map = (Map) OllamaJson.parse(line);
                Map message = map.get("message") instanceof Map ? (Map) map.get("message") : null;
                if (message != null) {
                    String content = string(message, "content");
                    if (content.length() > 0 && listener != null) {
                        listener.onToken(content);
                    }
                }
                if (Boolean.TRUE.equals(map.get("done"))) {
                    completion[0] = new ChatCompletion("", number(map, "eval_count"), number(map, "eval_duration"));
                }
            }
        });
        return completion[0];
    }

    public void pullModel(String modelName, final PullProgressListener listener) throws OllamaException {
        Map body = new LinkedHashMap();
        body.put("name", modelName);
        body.put("stream", Boolean.TRUE);
        httpClient.postLines("/api/pull", OllamaJson.toJson(body), new OllamaLineListener() {
            public void onLine(String line) {
                Map map = (Map) OllamaJson.parse(line);
                if (listener != null) {
                    listener.onProgress(new PullProgress(
                            string(map, "status"),
                            number(map, "completed"),
                            number(map, "total")));
                }
            }
        });
    }

    private void require(OllamaHttpResponse response) throws OllamaException {
        if (!response.isSuccessful()) {
            throw new OllamaException("HTTP " + response.getStatusCode() + ": " + response.getBody());
        }
    }

    private Map object(OllamaHttpResponse response) throws OllamaException {
        require(response);
        Object parsed = OllamaJson.parse(response.getBody());
        if (!(parsed instanceof Map)) {
            throw new OllamaException("Expected JSON object response.");
        }
        return (Map) parsed;
    }

    private List toMessageMaps(List<ChatMessage> messages) {
        List result = new ArrayList();
        if (messages == null) {
            return result;
        }
        for (int i = 0; i < messages.size(); i++) {
            ChatMessage message = messages.get(i);
            Map map = new LinkedHashMap();
            map.put("role", message.getRole());
            map.put("content", message.getContent());
            result.add(map);
        }
        return result;
    }

    private Model toModel(Map map) {
        return new Model(
                firstNonEmpty(string(map, "name"), string(map, "model")),
                string(map, "modified_at"),
                number(map, "size"),
                string(map, "digest"),
                details(map.get("details")));
    }

    private ModelDetails details(Object value) {
        if (!(value instanceof Map)) {
            return ModelDetails.empty();
        }
        Map map = (Map) value;
        return new ModelDetails(
                string(map, "format"),
                string(map, "family"),
                string(map, "families"),
                string(map, "parameter_size"),
                string(map, "quantization_level"));
    }

    private List list(Object value) {
        return value instanceof List ? (List) value : new ArrayList();
    }

    private List<Double> doubleList(List values) {
        List<Double> result = new ArrayList<Double>();
        for (int i = 0; i < values.size(); i++) {
            Object value = values.get(i);
            if (value instanceof Number) {
                result.add(Double.valueOf(((Number) value).doubleValue()));
            }
        }
        return result;
    }

    private static String string(Map map, String key) {
        Object value = map.get(key);
        return value == null ? "" : String.valueOf(value);
    }

    private static long number(Map map, String key) {
        Object value = map.get(key);
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        if (value == null) {
            return 0L;
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException ex) {
            return 0L;
        }
    }

    private static String firstNonEmpty(String first, String second) {
        return first != null && first.length() > 0 ? first : second;
    }
}
