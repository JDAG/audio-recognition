package com.github.davidmoten.ar;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;
import javax.sound.sampled.UnsupportedAudioFileException;

import rx.Observable;
import rx.Observable.OnSubscribe;
import rx.Subscriber;
import rx.functions.Func1;

import com.fastdtw.timeseries.TimeSeries;
import com.fastdtw.timeseries.TimeSeriesBase;
import com.fastdtw.timeseries.TimeSeriesBase.Builder;

public class Audio {

	public static Observable<Integer> readSignal(final InputStream is) {

		return Observable.create(new OnSubscribe<Integer>() {

			@Override
			public void call(Subscriber<? super Integer> sub) {
				try {
					// Load the Audio Input Stream from the file
					AudioInputStream audioInputStream = AudioSystem
							.getAudioInputStream(is);

					// Get Audio Format information
					AudioFormat audioFormat = audioInputStream.getFormat();

					// log details
					printAudioDetails(audioInputStream, audioFormat);

					// Write the sound to an array of bytes
					int bytesRead;
					byte[] data = new byte[8192];
					while (!sub.isUnsubscribed()
							&& (bytesRead = audioInputStream.read(data, 0,
									data.length)) != -1) {
						// Determine the original Endian encoding format
						boolean isBigEndian = audioFormat.isBigEndian();
						int n = bytesRead / 2;
						// convert each pair of byte values from the byte
						// array to an Endian value
						for (int i = 0; i < n * 2; i += 2) {
							int value = Util.valueFromTwoBytesEndian(data[i],
									data[i + 1], isBigEndian);
							if (sub.isUnsubscribed())
								return;
							else
								sub.onNext(value);
						}
					}
					sub.onCompleted();
				} catch (Exception e) {
					sub.onError(e);
				}
			}

		});
	}

	private static void printAudioDetails(AudioInputStream audioInputStream,
			AudioFormat audioFormat) {
		// Calculate the sample rate
		float sample_rate = audioFormat.getSampleRate();
		System.out.println("sample rate = " + sample_rate);

		// Calculate the length in seconds of the sample
		float T = audioInputStream.getFrameLength()
				/ audioFormat.getFrameRate();
		System.out
				.println("T = " + T + " (length of sampled sound in seconds)");

		// Calculate the number of equidistant points in time
		int num = (int) (T * sample_rate) / 2;
		System.out.println("n = " + num + " (number of equidistant points)");

		// Calculate the time interval at each equidistant point
		float h = (T / num);
		System.out.println("h = " + h
				+ " (length of each time interval in seconds)");
	}

	private static final int BUFFER_SIZE = 1024;

	public static void play(InputStream is) {

		// Load the Audio Input Stream from the file
		AudioInputStream audioInputStream = null;
		try {
			audioInputStream = AudioSystem.getAudioInputStream(is);
		} catch (UnsupportedAudioFileException e) {
			throw new RuntimeException(e);
		} catch (IOException e) {
			throw new RuntimeException(e);
		}

		// Get Audio Format information
		AudioFormat audioFormat = audioInputStream.getFormat();

		// Handle opening the line
		SourceDataLine line = null;
		DataLine.Info info = new DataLine.Info(SourceDataLine.class,
				audioFormat);
		try {
			line = (SourceDataLine) AudioSystem.getLine(info);
			line.open(audioFormat);
		} catch (LineUnavailableException e) {
			throw new RuntimeException(e);
		}

		// Start playing the sound
		line.start();

		// Write the sound to an array of bytes
		int nBytesRead = 0;
		byte[] abData = new byte[BUFFER_SIZE];
		while (nBytesRead != -1) {
			try {
				nBytesRead = audioInputStream.read(abData, 0, abData.length);
			} catch (IOException e) {
				e.printStackTrace();
			}
			if (nBytesRead >= 0) {
				line.write(abData, 0, nBytesRead);
			}
		}

		// close the line
		line.drain();
		line.close();

	}

	public static Observable<TimeSeries> timeSeries(Observable<Integer> signal,
			int frameSize, int skip, int numTriFilters, int numMfcCoefficients) {
		return signal
		// get frames
				.buffer(frameSize, skip)
				// full frames only
				.filter(Util.<Integer> hasSize(frameSize))
				// as array of double
				.map(Util.TO_DOUBLE_ARRAY)
				// emphasize higher frequencies
				.map(new PreEmphasisFunction())
				// apply filter to handle discontinuities at start and end
				.map(new HammingWindowFunction())
				// extract frequencies
				.map(toFft())
				// tri bandpass filter
				.map(new TriangularBandPassFilterBankFunction(numTriFilters,
						frameSize))
				// DCT
				.map(new DiscreteCosineTransformFunction(numMfcCoefficients,
						numTriFilters))
				// make a list of the frame mfccs
				.toList()
				// trim silence at beginning and end
				.map(trimSilenceAtBeginningAndEnd())
				// to TimeSeries
				.map(TO_TIME_SERIES);
	}

	public static Observable<TimeSeries> timeSeries(InputStream wave,
			int frameSize, int skip, int numTriFilters, int numMfcCoefficients) {
		return timeSeries(readSignal(wave), frameSize, skip, numTriFilters,
				numMfcCoefficients);
	}

	private static Func1<List<double[]>, List<double[]>> trimSilenceAtBeginningAndEnd() {
		return new Func1<List<double[]>, List<double[]>>() {
			@Override
			public List<double[]> call(List<double[]> list) {
				// trim from beginning
				int i = 0;
				final double POWER_THRESHOLD = 0.0;
				while (i < list.size() && list.get(i)[0] < POWER_THRESHOLD)
					i++;

				// trim from end
				int j = list.size() - 1;
				while (j > 0 && list.get(j)[0] < POWER_THRESHOLD)
					j--;
				return list.subList(i, j);
			}
		};

	}

	public static Observable<TimeSeries> timeSeries(InputStream wave) {
		int frameSize = 256;
		int skip = 100;
		return timeSeries(wave, frameSize, 26, 13, skip);
	}

	public static Func1<double[], double[]> toFft() {
		return new Func1<double[], double[]>() {

			@Override
			public double[] call(double[] signal) {
				return FFT.fftMagnitude(signal);
			}
		};
	}

	public static AudioFormat createAudioFormatStandard() {
		float sampleRate = 16000;
		int sampleSizeInBits = 8;
		int channels = 2;
		boolean signed = true;
		boolean bigEndian = true;
		AudioFormat format = new AudioFormat(sampleRate, sampleSizeInBits,
				channels, signed, bigEndian);
		return format;
	}

	public static Observable<byte[]> microphoneRaw(int bufferSize,
			AudioFormat format) {

		return Observable.create(new MicrophoneOnSubscribe(4096, format));
	}

	public static Observable<Integer> microphone() {
		int bufferSize = 4096;
		final AudioFormat format = createAudioFormatStandard();
		// get raw bytes from microphone
		return microphoneRaw(bufferSize, format)
		// byte pairs as integers
				.flatMap(new Func1<byte[], Observable<Integer>>() {

					@Override
					public Observable<Integer> call(byte[] bytes) {
						return Observable.from(toIntegers(format, bytes));
					}
				});
	}

	public static List<Integer> toIntegers(final AudioFormat format,
			byte[] bytes) {
		// Determine the original Endian encoding format
		boolean isBigEndian = format.isBigEndian();
		int n = bytes.length / 2;
		// convert each pair of byte values from the byte
		// array to an Endian value
		List<Integer> list = new ArrayList<Integer>(n);
		for (int i = 0; i < n * 2; i += 2) {
			int value = Util.valueFromTwoBytesEndian(bytes[i], bytes[i + 1],
					isBigEndian);
			list.add(value);
		}
		return list;
	}

	public static final Func1<List<double[]>, TimeSeries> TO_TIME_SERIES = new Func1<List<double[]>, TimeSeries>() {

		@Override
		public TimeSeries call(List<double[]> list) {
			Builder builder = TimeSeriesBase.builder();
			int time = 0;
			for (double[] mfccs : list) {
				builder = builder.add(time, dropFirst(mfccs));
				time += 1;
			}
			return builder.build();
		}
	};

	private static double[] dropFirst(double[] x) {
		return Arrays.copyOfRange(x, 1, x.length);
	}
}
