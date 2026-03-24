package cn.gtemc.craftengine.util;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.Map;

public final class GsonHelper {
    private final Gson gson;

    private GsonHelper() {
        this.gson = new GsonBuilder()
                .disableHtmlEscaping()
                .create();
    }

    private Gson getGson() {
        return gson;
    }

    public static Gson get() {
        return SingletonHolder.INSTANCE.getGson();
    }

    public static Map<String, String> parseJson(InputStream is) {
        return get().fromJson(
                new InputStreamReader(is),
                new TypeToken<Map<String, String>>(){}.getType()
        );
    }

    public static String toJson(Object obj) {
        return get().toJson(obj);
    }

    private static class SingletonHolder {
        private static final GsonHelper INSTANCE = new GsonHelper();
    }
}
