package com.example.learnglide.util;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import okhttp3.Interceptor;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

public class ProgressInterceptor implements Interceptor {

    static final Map<String, ProgressListener> MAP_LISTENER = new HashMap<>();

    public static void addListener(String url, ProgressListener listener) {
        MAP_LISTENER.put(url, listener);
    }

    public static void removeListener(String url) {
        MAP_LISTENER.remove(url);
    }

    @Override
    public Response intercept(Chain chain) throws IOException {
        Request request = chain.request();
        Response response = chain.proceed(request);

        String url = request.url().toString();
        ResponseBody body = response.body();
        Response newResponse = response.newBuilder().body(new ProgressResponseBody(url, body)).build();
        return newResponse;
    }


}
