package com.example.testapp;

import android.app.Activity;

public class MainActivity extends Activity {
    private AuthManager auth;

    @Override
    public void onCreate(Object bundle) {
        super.onCreate(bundle);
        auth = new AuthManager();
        auth.login("test", "test");
    }
}
