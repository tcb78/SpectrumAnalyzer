package com.example.taichiabe.spectrumanalyzer;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Locale;

import com.github.mikephil.charting.charts.LineChart;
import com.github.mikephil.charting.components.AxisBase;
import com.github.mikephil.charting.components.XAxis;
import com.github.mikephil.charting.components.YAxis;
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.data.LineData;
import com.github.mikephil.charting.data.LineDataSet;
import com.github.mikephil.charting.formatter.IAxisValueFormatter;
import com.github.mikephil.charting.interfaces.datasets.ILineDataSet;

import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Handler;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.util.Log;
import android.widget.CompoundButton;
import android.widget.CompoundButton.OnCheckedChangeListener;
import android.widget.Switch;

public class MainActivity extends AppCompatActivity implements OnCheckedChangeListener {

    //サンプリングレート
    public static final int SAMPLING_RATE = 44100;
    //FFTのフレーム数（2の累乗）
    public static final int FFT_SIZE = 4096;
    //デシベルベースラインの設定
    public static final double DB_BASELINE = Math.pow(2, 15) * FFT_SIZE * Math.sqrt(2);
    //分解能の計算
    public static final double RESOLUTION = SAMPLING_RATE / (double) FFT_SIZE;
    private AudioRecord audioRec = null;
    boolean isRecording = false;
    Thread fft;

    public LineChart lineChart;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        Switch receivingSwitch = findViewById(R.id.receivingSwitch);
        receivingSwitch.setOnCheckedChangeListener(this);

        lineChart = findViewById(R.id.line_chart);
        initChart();
    }

    @Override
    public void onResume() {
        super.onResume();
    }

    @Override
    public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {

        if(isChecked) {

            final Handler handler = new Handler();

            //実験用デバイスではminBufSize = 3584
            final int MIN_BUFFER_SIZE = AudioRecord.getMinBufferSize(
                    SAMPLING_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT);

            final int RECORD_BUFFER_SIZE = MIN_BUFFER_SIZE * 4;

            //最終的にFFTクラスの4096以上を確保するためbufferSizeInBytes = RecvBufSize * 4
            audioRec = new AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLING_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    RECORD_BUFFER_SIZE);

            audioRec.startRecording();
            isRecording = true;

            //フーリエ解析スレッドを生成
            fft = new Thread(new Runnable() {
                @Override
                public void run() {

                    byte[] recordData = new byte[RECORD_BUFFER_SIZE];
                    while (isRecording) {

                        audioRec.read(recordData, 0, recordData.length);
                        //エンディアン変換
                        short[] shortData = toLittleEndian(recordData, RECORD_BUFFER_SIZE);
                        //FFTクラスの作成と値の引き出し
                        double[] fftData = fastFourierTransform(shortData);
                        //パワースペクトル・デシベルの計算
                        final double[] decibelFrequencySpectrum = computePowerSpectrum(fftData);
                        //TODO:ここでArrayListに代入
                        // Handlerを使用してメイン(UI)スレッドに処理を依頼する
                        handler.post(new Runnable() {
                            @Override
                            public void run() {
                                setData(decibelFrequencySpectrum);
                            }
                        });


                    }
                    audioRec.stop();
                    audioRec.release();
                }

            });
            //スレッドのスタート
            fft.start();

        } else {

            if(audioRec.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                audioRec.stop();
                //audioRec.release();
                isRecording = false;
            }
        }
    }

    @Override
    public void onPause() {
        super.onPause();

        if(audioRec.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
            audioRec.stop();
            isRecording = false;
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        if(audioRec.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
            audioRec.stop();
            audioRec.release();
            isRecording = false;
        }
    }

    public void initChart() {

        lineChart.setTouchEnabled(true);
        lineChart.setDragEnabled(false);

        // Grid背景色
        lineChart.setDrawGridBackground(true);

        // no description text
        lineChart.getDescription().setEnabled(true);

        lineChart.setBackgroundColor(Color.LTGRAY);

        LineData data = new LineData();
        data.setValueTextColor(Color.BLACK);

        // add empty data
        lineChart.setData(data);

        // Grid縦軸を破線
        XAxis xAxis = lineChart.getXAxis();
        xAxis.setAxisMaximum(2048);
        xAxis.setAxisMinimum(0);
        xAxis.enableGridDashedLine(10f, 10f, 0f);
        xAxis.setPosition(XAxis.XAxisPosition.BOTTOM);

        YAxis leftAxis = lineChart.getAxisLeft();
        // Y軸最大最小設定
        leftAxis.setAxisMaximum(0f);
        leftAxis.setAxisMinimum(-150f);
        // Grid横軸を破線
        leftAxis.enableGridDashedLine(10f, 10f, 0f);
        leftAxis.setDrawZeroLine(true);

        // 右側の目盛り
        lineChart.getAxisRight().setEnabled(false);
    }

    /**
     * エンディアン変換
     * @param   buf     受信バッファデータ
     * @param   bufferSize  受信バッファサイズ
     * @return  shortData   エンディアン変換後short型データ
     */
    public short[] toLittleEndian(byte[] buf, int bufferSize) {

        //エンディアン変換
        //配列bufをもとにByteBufferオブジェクトbfを作成
        ByteBuffer bf = ByteBuffer.wrap(buf);
        //バッファをクリア（データは削除されない）
        bf.clear();
        //リトルエンディアンに変更
        bf.order(ByteOrder.LITTLE_ENDIAN);
        short[] shortData = new short[bufferSize / 2];
        //位置から容量まで
        for (int i = bf.position(); i < bf.capacity() / 2; i++) {
            //short値を読むための相対getメソッド
            //現在位置の2バイトを読み出す
            shortData[i] = bf.getShort();
        }
        return shortData;
    }

    /**
     * 高速フーリエ変換
     * @param   shortData   エンディアン変換後データ
     * @return  fftData     フーリエ変換後データ
     */
    public double[] fastFourierTransform(short[] shortData) {

        //FFTクラスの作成と値の引き渡し
        FFT4g fft = new FFT4g(FFT_SIZE);
        double[] fftData = new double[FFT_SIZE];
        for(int i = 0; i < FFT_SIZE; i++) {
            fftData[i] = (double) shortData[i];
        }
        fft.rdft(1, fftData);

        return fftData;
    }

    /**
     * パワースペクトル・デシベルの計算
     * @param   fftData         フーリエ変換後のデータ
     * @return  decibelFrequencySpectrum    デシベル値
     */
    public double[] computePowerSpectrum(double[] fftData) {

        //パワースペクトル・デシベルの計算
        double[] powerSpectrum = new double[FFT_SIZE / 2];
        //DeciBel Frequency Spectrum
        double[] decibelFrequencySpectrum = new double[FFT_SIZE / 2];
        for(int i = 0; i < FFT_SIZE; i += 2) {
            //dbfs[i / 2] = (int) (20 * Math.log10(Math.sqrt(Math.pow(FFTdata[i], 2) + Math.pow(FFTdata[i + 1], 2)) / dB_baseline));
            powerSpectrum[i / 2] = Math.sqrt(Math.pow(fftData[i], 2) + Math.pow(fftData[i + 1], 2));
            decibelFrequencySpectrum[i / 2] = (int) (20 * Math.log10(powerSpectrum[i / 2] / DB_BASELINE));
        }
        return decibelFrequencySpectrum;
    }

    public void setData(double[] data) {

        ArrayList<Entry> values = new ArrayList<>();

        for (int i = 0; i < data.length; i++) {
            values.add(new Entry(i, (int)data[i], null, null));
        }

        LineDataSet set1;

        if (lineChart.getData() != null &&
                lineChart.getData().getDataSetCount() > 0) {

            set1 = (LineDataSet) lineChart.getData().getDataSetByIndex(0);
            set1.setValues(values);
            lineChart.getData().notifyDataChanged();
            lineChart.notifyDataSetChanged(); // let the chart know it's data changed
            lineChart.invalidate(); // refresh
        } else {
            // create a dataset and give it a type
            set1 = new LineDataSet(values, "Spectrum");

            set1.setDrawIcons(false);
            set1.setColor(Color.rgb(0, 0, 240));
            set1. setDrawCircles(false);
            //set1.setCircleColor(Color.BLACK);
            set1.setLineWidth(0.5f);
            //set1.setCircleRadius(0.25f);
            //set1.setDrawCircleHole(false);
            set1.setValueTextSize(0f);
            set1.setDrawFilled(false);
            set1.setFormLineWidth(1f);
            set1.setFormLineDashEffect(new DashPathEffect(new float[]{10f, 5f}, 0f));
            set1.setFormSize(15.f);
            set1.setDrawValues(true);

            //set1.setFillColor(Color.rgb(10, 10, 240));

            ArrayList<ILineDataSet> dataSets = new ArrayList<>();
            dataSets.add(set1); // add the datasets

            // create a data object with the datasets
            LineData lineData = new LineData(dataSets);

            // set data
            lineChart.setData(lineData);
        }
    }
}
