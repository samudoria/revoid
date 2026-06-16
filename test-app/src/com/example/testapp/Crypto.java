package com.example.testapp;

public class Crypto {
    private static final String SECRET = "SUPER_SECRET_KEY";

    public static String decrypt(String data) {
        // Dummy decryption logic
        return data + "_" + SECRET;
    }
}
