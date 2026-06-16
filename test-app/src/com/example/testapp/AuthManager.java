package com.example.testapp;

public class AuthManager {
    public boolean login(String user, String pass) {
        String decrypted = Crypto.decrypt(pass);
        return "admin".equals(user) && decrypted != null;
    }
}
