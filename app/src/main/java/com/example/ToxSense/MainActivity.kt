package com.example.ToxSense

import android.content.Intent
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.widget.Button


class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        title = "ToxSense"

        val continuebutton = findViewById<Button>(R.id.b_continue)
        continuebutton.setOnClickListener {

            val intent = Intent(this, Home::class.java)
            startActivity(intent)}
    }

}