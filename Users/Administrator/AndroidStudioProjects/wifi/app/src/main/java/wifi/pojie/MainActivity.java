private void setupClickListeners() {
    executeButton.setOnClickListener(v -> {
        if (isRunning) {
            stopRunningCommand();
        } else {
            if (!ShizukuHelper.isShizukuAvailable()) {
                Toast.makeText(this, "Shizuku服务未启动", Toast.LENGTH_LONG).show();
                return;
            }
            
            if (ShizukuHelper.checkPermission()) {
                Shizuku.requestPermission(REQUEST_CODE_PERMISSION);
            } else {

                commandOutput.setText("      _      __                 _        \n" +
                        "     | |___ / _|_ __ ___  _   _| |_ __ _ \n" +
                        "  _  | / __| |_| '_ ` _ \\| | | | __/ _` |\n" +
                        " | |_| \\__ \\  _| | | | | | |_| | || (_| |\n" +
                        "  \\___/|___/_| |_| |_| |_|\\__, |\\__\\__, |\n" +
                        "                          |___/    |___/ \n" +
                        "==========================================\n");
                isRunning = true;
                runOnUiThread(() -> executeButton.setText("结束运行"));

                currentWifiPojie = new WifiPojie(
                        wifiSsid.getText(),
                        dictionary,
                        Integer.parseInt(startLine.getText().toString()),
                        Integer.parseInt(String.valueOf(tryTime.getText())),
                        output -> runOnUiThread(() -> commandOutput.append("\n"+output)),
                        (progress, total, text) -> {},
                        this::stopRunningCommand
                );

            }
        }
    });

    dictionarySelect.setOnClickListener(v -> {
        Log.d(TAG, "Selecting dictionary");
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("text/plain");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        filePickerLauncher.launch(Intent.createChooser(intent, "选择字典文件"));
    });

    Button copyBtn = findViewById(R.id.copybtn);
    Button clearBtn = findViewById(R.id.clearbtn);
    Button chooseWifiBtn = findViewById(R.id.button6);
    
    copyBtn.setOnClickListener(v -> copyTextToClipboard());
    
    clearBtn.setOnClickListener(v -> commandOutput.setText(""));
    
    chooseWifiBtn.setOnClickListener(v -> {
        Intent intent = new Intent(MainActivity.this, WifiSelectionDialog.class);
        startActivityForResult(intent, REQUEST_CODE_PICK_WIFI);
    });
}

@Override
protected void onActivityResult(int requestCode, int resultCode, Intent data) {
    super.onActivityResult(requestCode, resultCode, data);
    
    if (requestCode == REQUEST_CODE_PICK_FILE && resultCode == RESULT_OK) {
        Uri uri = data.getData();
        if (uri != null) {
            try {
                dictionary = readDictionaryFromFile(uri);
                Toast.makeText(this, "成功加载字典，共" + dictionary.length + "个密码", Toast.LENGTH_SHORT).show();
            } catch (IOException e) {
                Log.e(TAG, "读取字典文件失败", e);
                Toast.makeText(this, "读取字典文件失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        }
    }
    
    if (requestCode == REQUEST_CODE_PICK_WIFI && resultCode == RESULT_OK) {
        String selectedWifi = data.getStringExtra("selected_wifi");
        if (selectedWifi != null) {
            wifiSsid.setText(selectedWifi);
        }
    }
}