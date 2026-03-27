package com.tnmower.tnmower.ui;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.Intent;
import android.os.Bundle;
import android.widget.ArrayAdapter;
import android.widget.ListView;

import androidx.appcompat.app.AppCompatActivity;

import java.util.ArrayList;
import java.util.Set;

public class DeviceListActivity extends AppCompatActivity {

    private BluetoothAdapter btAdapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        ListView listView = new ListView(this);
        setContentView(listView);

        btAdapter = BluetoothAdapter.getDefaultAdapter();

        Set<BluetoothDevice> paired = btAdapter.getBondedDevices();

        ArrayList<String> list = new ArrayList<>();
        ArrayList<String> macList = new ArrayList<>();

        for (BluetoothDevice d : paired) {
            list.add(d.getName() + "\n" + d.getAddress());
            macList.add(d.getAddress());
        }

        ArrayAdapter<String> adapter =
                new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, list);

        listView.setAdapter(adapter);

        listView.setOnItemClickListener((parent, view, position, id) -> {

            String mac = macList.get(position);

            Intent result = new Intent();
            result.putExtra("MAC", mac);

            setResult(RESULT_OK, result);
            finish();
        });
    }
}

