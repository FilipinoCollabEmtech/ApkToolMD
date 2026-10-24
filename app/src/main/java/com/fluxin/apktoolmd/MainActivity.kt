package com.fluxin.apktoolmd

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.fluxin.apktoolmd.modmenu.ModMenuLoader
import com.fluxin.apktoolmd.modmenu.ModMenuOverlayService
import com.fluxin.apktoolmd.ui.DebugFragment
import com.fluxin.apktoolmd.ui.HomeFragment
import com.fluxin.apktoolmd.ui.SettingsFragment
import com.fluxin.apktoolmd.ui.ProcessEventFragment
import com.google.android.material.bottomnavigation.BottomNavigationView

class MainActivity : AppCompatActivity() {

    private val storagePermCode = 1001
    private var currentTab = R.id.nav_home

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Start R3DNETWORK mod menu (loads libmodmenu.so + installs hooks)
        try {
            ModMenuLoader.start(this)
        } catch (e: Exception) {
            // Ignore - mod menu is optional
        }

        // Start floating mod menu overlay service only if overlay permission granted
        try {
            val intent = Intent(this, ModMenuOverlayService::class.java)
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.SYSTEM_ALERT_WINDOW)
                == PackageManager.PERMISSION_GRANTED) {
                startService(intent)
            }
        } catch (e: Exception) {
            // Ignore - overlay service is optional
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(
                    Manifest.permission.WRITE_EXTERNAL_STORAGE,
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.SYSTEM_ALERT_WINDOW
                ),
                storagePermCode
            )
        }

        val bottomNav = findViewById<BottomNavigationView>(R.id.bottom_nav)

        bottomNav.setOnNavigationItemSelectedListener { item ->
            val fragment: Fragment = when (item.itemId) {
                R.id.nav_home -> HomeFragment()
                R.id.nav_debug -> DebugFragment()
                R.id.nav_settings -> SettingsFragment()
                R.id.nav_process_event -> ProcessEventFragment()
                else -> return@setOnNavigationItemSelectedListener false
            }
            currentTab = item.itemId
            supportFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, fragment)
                .commit()
            // the burger menu only belongs to the home tab
            invalidateOptionsMenu()
            true
        }

        if (savedInstanceState == null) {
            bottomNav.selectedItemId = R.id.nav_home
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.home_menu, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        menu.findItem(R.id.action_menu)?.isVisible = currentTab == R.id.nav_home
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.action_menu) {
            val home = supportFragmentManager.findFragmentById(R.id.fragment_container) as? HomeFragment
            home?.openMenuPanel()
            return true
        }
        return super.onOptionsItemSelected(item)
    }
}
