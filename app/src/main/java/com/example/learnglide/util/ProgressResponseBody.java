package com.example.learnglide.util;

import android.support.annotation.Nullable;
import android.util.Log;

import java.io.IOException;

import okhttp3.MediaType;
import okhttp3.ResponseBody;
import okio.Buffer;
import okio.BufferedSource;
import okio.ForwardingSource;
import okio.Okio;
import okio.Source;

public class ProgressResponseBody extends ResponseBody {

    private final String TAG = getClass().getSimpleName();

    private BufferedSource bufferedSource;

    private ResponseBody responseBody;

    private ProgressListener listener;

    public ProgressResponseBody(String url, ResponseBody responseBody) {
        this.responseBody = responseBody;
        listener = ProgressInterceptor.MAP_LISTENER.get(url);
    }

    @Nullable
    @Override
    public MediaType contentType() {
        return responseBody.contentType();
    }

    @Override
    public long contentLength() {
        return responseBody.contentLength();
    }

    @Override
    public BufferedSource source() {
        if (null == bufferedSource)
            bufferedSource = Okio.buffer(new ProgressSource(responseBody.source()));
        return bufferedSource;
    }

    private class ProgressSource extends ForwardingSource {

        long totalBytesRead = 0;

        int currentProgress;

        ProgressSource(Source source) {
            super(source);
        }

        @Override
        public long read(Buffer sink, long byteCount) throws IOException {
            int progress;
            long bytesRead = super.read(sink, byteCount);
            long fullLength = contentLength();

            if (-1 == bytesRead) {
                totalBytesRead = fullLength;
                progress = 100;
            } else {
                totalBytesRead += bytesRead;
                progress = (int) (100f * totalBytesRead / fullLength);
            }

            Log.d(TAG, "download progress = " + progress);
            if (null != listener && progress != currentProgress) {
                listener.onProgress(progress);
            }

            if (null != listener && progress == 100) {
                listener = null;
            }

            currentProgress = progress;
            return progress;
        }
    }
}
