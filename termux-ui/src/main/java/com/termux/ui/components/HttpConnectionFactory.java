package com.termux.ui.components;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;

public interface HttpConnectionFactory {
    HttpURLConnection open(URL url) throws IOException;
}
