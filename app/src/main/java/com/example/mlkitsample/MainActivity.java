package com.example.mlkitsample;

import android.Manifest;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Point;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import com.google.android.gms.tasks.OnFailureListener;
import com.google.android.gms.tasks.OnSuccessListener;
import com.google.firebase.ml.common.FirebaseMLException;
import com.google.firebase.ml.common.modeldownload.FirebaseLocalModelSource;
import com.google.firebase.ml.common.modeldownload.FirebaseModelManager;
import com.google.firebase.ml.custom.FirebaseModelDataType;
import com.google.firebase.ml.custom.FirebaseModelInputOutputOptions;
import com.google.firebase.ml.custom.FirebaseModelInputs;
import com.google.firebase.ml.custom.FirebaseModelInterpreter;
import com.google.firebase.ml.custom.FirebaseModelOptions;
import com.google.firebase.ml.custom.FirebaseModelOutputs;

import java.io.FileDescriptor;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MainActivity extends AppCompatActivity {

    // 撮影した写真のURI
    private Uri image_uri;
    // 分類結果に使用する犬種ラベル
    private String[] dog_labels;
    // 分類結果に表示する文字列
    private String true_text;
    private String false_text;

    // Firebaseの分類に使用するクラス
    private FirebaseModelInterpreter interpreter;
    private FirebaseModelInputOutputOptions inputOutputOptions;

    // カメラ、アルバムのアクティビティの識別コード
    private static final int IMAGE_CAPTURE_CODE = 200;
    private static final int READ_REQUEST_CODE = 42;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // ボタンのイベント生成
        Button cameraBtnClk = findViewById(R.id.cameraButton);
        cameraBtnListener cameraBtnListener = new cameraBtnListener();
        cameraBtnClk.setOnClickListener(cameraBtnListener);

        Button albumBtnClk = findViewById(R.id.albumButton);
        albumBtnListener albumBtnListener = new albumBtnListener();
        albumBtnClk.setOnClickListener(albumBtnListener);


        // 分類結果に表示する文字列の準備
        dog_labels = getResources().getStringArray(R.array.dog_breed);
        true_text = getResources().getString(R.string.true_text);
        false_text = getResources().getString(R.string.false_text);

        // モデルの利用の準備
        // ローカルモデルソースの登録
        FirebaseLocalModelSource localModelSource =
                new FirebaseLocalModelSource.Builder("dog_breed_classifier")
                        .setAssetFilePath("dog_breed_classifier.tflite")
                        .build();
        boolean successfulRegisterModel= FirebaseModelManager.getInstance().registerLocalModelSource(localModelSource);

        // 登録に成功したら、分類器と分類器の入力と出力を生成
        if (successfulRegisterModel) {
            try {
                FirebaseModelOptions options = new FirebaseModelOptions.Builder()
                        .setLocalModelName("dog_breed_classifier")
                        .build();
                interpreter = FirebaseModelInterpreter.getInstance(options);

                inputOutputOptions = new FirebaseModelInputOutputOptions.Builder()
                        .setInputFormat(0, FirebaseModelDataType.FLOAT32, new int[]{1, 224, 224, 3})
                        .setOutputFormat(0, FirebaseModelDataType.FLOAT32, new int[]{1, 10})
                        .build();

            } catch (FirebaseMLException e) {
                Log.e("NotRegisterFirebaseModel", "Firebasemodelの登録できませんでした。エラーメッセージ：" + e.getLocalizedMessage());
            }
        }
    }

    // カメラ撮影ボタンの処理
    private class cameraBtnListener implements View.OnClickListener {
        @Override
        public void onClick(View view) {
            // URIオブジェクト生成する為に、ContentResolverを使用
            ContentResolver resolver = getContentResolver();
            image_uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, new ContentValues());
            // アクティビティを起動する為に、Intentオブジェクトを使用
            Intent intent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
            // Extra情報として image_uriを設定
            intent.putExtra(MediaStore.EXTRA_OUTPUT, image_uri);
            // アクティビティを起動
            startActivityForResult(intent, IMAGE_CAPTURE_CODE);
        }
    }

    // アルバムボタンの処理
    private class albumBtnListener implements View.OnClickListener {
        @Override
        public void onClick(View view) {

            // アルバムの使用の許可を求める
            if (ActivityCompat.checkSelfPermission(MainActivity.this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) {
                String[] permission = {Manifest.permission.WRITE_EXTERNAL_STORAGE};
                ActivityCompat.requestPermissions(MainActivity.this, permission, 2000);
                return;
            }

            // 写真を選択して、アクティビティを起動
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("image/*");
            startActivityForResult(intent, READ_REQUEST_CODE);
        }
    }


    // アルバムの使用が許可された時、アルバムを開く
    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode == 2000 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            albumBtnListener albumBtnListener = new albumBtnListener();
            albumBtnListener.onClick(null);
        }
    }

    // カメラ、アルバムアクティビティが起動した際の処理
    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent resultData) {

        super.onActivityResult(requestCode, resultCode, resultData);
        ImageView imageView = findViewById(R.id.imageView);
        int view_width = imageView.getWidth();
        int view_height = imageView.getHeight();

        // カメラ撮影の処理
        if (requestCode == IMAGE_CAPTURE_CODE) {
            // 写真を撮影した場合
            if (resultCode == RESULT_OK) {
                Uri uri = image_uri;
                // 画像のURIと出力画面のサイズからBitmapを生成
                Bitmap bitmap = createBitmap(uri, view_width, view_height);
                // 犬種の分類結果を表示
                outPutDogBreed(bitmap);
                // 画像を画面に表示
                imageView.setImageBitmap(bitmap);
            }
            // 撮影した画像を削除
            getContentResolver().delete(image_uri, null, null);

            // アルバムを開く処理
        } else if (requestCode == READ_REQUEST_CODE && resultCode == RESULT_OK) {
            if (resultData != null) {
                Uri uri = resultData.getData();
                // 画像のURIと出力画面のサイズからBitmapを生成
                Bitmap bitmap = createBitmap(uri, view_width, view_height);
                // 犬種の分類結果を表示
                outPutDogBreed(bitmap);
                // 画像を画面に表示
                imageView.setImageBitmap(bitmap);
            }
        }
    }

    // URIから取得したBitmapを指定したサイズと正しい向きに調節する関数
    private Bitmap createBitmap(Uri uri, int scaled_bitmap_width, int scaled_bitmap_height) {

        Bitmap bitmap = null;
        int orientation = 1;
        Matrix matrix = new Matrix();

        // URIからBitmapを得る
        try (ParcelFileDescriptor parcelFileDescriptor = getContentResolver().openFileDescriptor(uri, "r")) {
            FileDescriptor fileDescriptor = parcelFileDescriptor.getFileDescriptor();
            bitmap = BitmapFactory.decodeFileDescriptor(fileDescriptor);
        } catch (IOException e) {
            Log.e("NotGetBitmap", "指定したURIで画像を取得できませんでした。URIは" + uri + "になっています。エラーメッセージ：" + e.getMessage());
        }

        // URIから画像の向き情報を取得
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            ExifInterface exifInterface = new ExifInterface(in);
            orientation = exifInterface.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_UNDEFINED);
        } catch (IOException e) {
            Log.e("NotGetOrientation", "指定したURIで正しいorientationを取得できませんでした。エラーメッセージ："+e.getMessage());
        }
        switch (orientation) {
            case 6:
                matrix.postRotate(90);
                break;
            case 3:
                matrix.postRotate(180);
                break;
            case 8:
                matrix.postRotate(270);
                break;
            default:
                matrix.postRotate(0);
        }

        // 出力画面のサイズと取得した向き情報を元に、画像を調節
        Bitmap scaledBitmap = Bitmap.createScaledBitmap(bitmap, scaled_bitmap_width, scaled_bitmap_height, true);
        Bitmap rotatedBitmap = Bitmap.createBitmap(scaledBitmap, 0, 0, scaledBitmap.getWidth(), scaledBitmap.getHeight(), matrix, true);
        return rotatedBitmap;
    }

    // 画像をモデルが受け付けるデータに加工
    private float[][][][] image2inputData(Bitmap image, Point size) {

        int width = size.x;
        int height = size.y;
        int channel = 3;

        Bitmap bitmap = Bitmap.createScaledBitmap(image, width, height, true);
        float[][][][] input = new float[1][height][width][channel];

        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                int pixel = bitmap.getPixel(x, y);
                input[0][y][x][0] = Color.red(pixel) / 255.0f;
                input[0][y][x][1] = Color.green(pixel) / 255.0f;
                input[0][y][x][2] = Color.blue(pixel) / 255.0f;
            }
        }
        return input;
    }

    // 画像分類
    private void outPutDogBreed(Bitmap image) {
        float[][][][] input = image2inputData(image, new Point(224, 224));
        try {
            FirebaseModelInputs inputs = new FirebaseModelInputs.Builder().add(input).build();
            interpreter.run(inputs, inputOutputOptions)
                    .addOnSuccessListener(
                            new OnSuccessListener<FirebaseModelOutputs>() {
                                @Override
                                public void onSuccess(FirebaseModelOutputs firebaseModelOutputs) {

                                    // 分類結果の取得し、HashMapに変換
                                    float[][] output = firebaseModelOutputs.getOutput(0);
                                    Map<Integer, Float> hashMap = new HashMap<>();
                                    float[] inArray = output[0];
                                    for (int i = 0; i < inArray.length; i++) {
                                        hashMap.put(i, inArray[i]);
                                    }

                                    // 信頼度順にソート
                                    List<Map.Entry<Integer, Float>> entries =
                                            new ArrayList<>(hashMap.entrySet());
                                    Collections.sort(entries, new Comparator<Map.Entry<Integer, Float>>() {
                                        @Override
                                        public int compare(Map.Entry<Integer, Float> o1, Map.Entry<Integer, Float> o2) {
                                            return (o2.getValue()).compareTo(o1.getValue());
                                        }
                                    });

                                    // 最高の信頼度とその犬種を取得し、出力文を表示
                                    String text;
                                    Map.Entry<Integer, Float> s = entries.get(0);
                                    int index = s.getKey();
                                    float accuracy = s.getValue();
                                    if (accuracy >= 0.9) {
                                        text = String.format(true_text, dog_labels[index]);
                                    } else {
                                        text = false_text;
                                    }
                                    TextView textView = findViewById(R.id.textView);
                                    textView.setText(text);
                                }
                            })
                    .addOnFailureListener(
                            new OnFailureListener() {
                                @Override
                                public void onFailure(@NonNull Exception e) {
                                    Log.e("NotRunInterpreter", "分類できませんでした。エラーメッセージ：" + e.getMessage());
                                }
                            }
                    );
        } catch (FirebaseMLException e) {
            Log.e("NotBuildFirebaseModelInputs", "FirebaseModelInputsのビルドができませんでした。エラーメッセージ：" + e.getMessage());
        }
    }
}
