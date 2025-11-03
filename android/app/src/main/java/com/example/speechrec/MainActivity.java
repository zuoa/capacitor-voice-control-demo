package com.example.speechrec;

import com.getcapacitor.BridgeActivity;
import android.os.Bundle;
import com.example.speechrec.baiduasr.BaiduAsrPlugin;
import com.example.speechrec.sherpaonnx.SherpaOnnxPlugin;

public class MainActivity extends BridgeActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // Register plugin BEFORE bridge initialization so it's available at startup
        registerPlugin(BaiduAsrPlugin.class);
        registerPlugin(SherpaOnnxPlugin.class);
        super.onCreate(savedInstanceState);
    }
}
