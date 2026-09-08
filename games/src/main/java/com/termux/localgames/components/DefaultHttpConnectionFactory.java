package com.termux.localgames.components;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;

public final class DefaultHttpConnectionFactory implements HttpConnectionFactory {
    @Override
    public HttpURLConnection open(URL url) throws IOException {
        return (HttpURLConnection) url.openConnection();
    }
}
