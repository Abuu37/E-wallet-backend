package com.application.e_wallet.auth.service;

import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

@Service
public class RefreshTokenHashService {

    public String hash(String refreshToken) {
         try{
             MessageDigest digest = MessageDigest.getInstance("SHA-256");

             byte[] hash = digest.digest(refreshToken.getBytes(StandardCharsets.UTF_8));

             StringBuilder hex = new StringBuilder();

             for(byte b : hash){
                 hex.append(String.format("%02x", b));
             }

             return hex.toString();

        } catch(NoSuchAlgorithmException e){
             throw new RuntimeException(
                     "SHA-256 hash algorithm not supported", e
             );
        }
    }
}
