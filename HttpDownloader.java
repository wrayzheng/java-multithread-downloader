/*
 * ----------------------------------------------------
 * Copyright (c) 2018, Wray Zheng. All Rights Reserved.
 * Distributed under the BSD License.
 * ----------------------------------------------------
 */

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * @author: Wray Zheng
 * @date: 2018-04-23
 * @description: A multi-threaded downloader
 */
public class HttpDownloader {

	private boolean resumable;
	private URL url;
	private File localFile;
	private int[] endPoint;
	private Object waiting = new Object();
	private AtomicInteger downloadedBytes = new AtomicInteger(0);
	private AtomicInteger aliveThreads = new AtomicInteger(0);
	private boolean multithreaded = true;
	private int fileSize = 0;
	private int THREAD_NUM = 5;
	private int TIME_OUT = 5000;
	private final int MIN_SIZE = 2 << 20;

	public static void main(String[] args) throws IOException {
		String url = "http://mirrors.163.com/debian/ls-lR.gz";
		new HttpDownloader(url, "D:/ls-lR.gz", 5, 5000).get();
	}

	public HttpDownloader(String Url, String localPath) throws MalformedURLException {
		this.url = new URL(Url);
		this.localFile = new File(localPath);
	}

	public HttpDownloader(String Url, String localPath,
			int threadNum, int timeout) throws MalformedURLException {
		this(Url, localPath);
		this.THREAD_NUM = threadNum;
		this.TIME_OUT = timeout;
	}

	//开始下载文件
	public void get() throws IOException {
		long startTime = System.currentTimeMillis();

		resumable = supportResumeDownload();
		if (!resumable || THREAD_NUM == 1|| fileSize < MIN_SIZE) multithreaded = false;
		if (!multithreaded) {
			new DownloadThread(0, 0, fileSize - 1).start();;
		}
		else {
			endPoint = new int[THREAD_NUM + 1];
			int block = fileSize / THREAD_NUM;
			for (int i = 0; i < THREAD_NUM; i++) {
				endPoint[i] = block * i;
			}
			endPoint[THREAD_NUM] = fileSize;
			for (int i = 0; i < THREAD_NUM; i++) {
				new DownloadThread(i, endPoint[i], endPoint[i + 1] - 1).start();
			}
		}

		startDownloadMonitor();

		//等待 downloadMonitor 通知下载完成
		try {
			synchronized(waiting) {
				waiting.wait();
			}
		} catch (InterruptedException e) {
			System.err.println("Download interrupted.");
		}

		cleanTempFile();

		long timeElapsed = System.currentTimeMillis() - startTime;
		System.out.println("* File successfully downloaded.");
		System.out.println(String.format("* Time used: %.3f s, Average speed: %d KB/s",
				timeElapsed / 1000.0, downloadedBytes.get() / timeElapsed));
	}

	//检测目标文件是否支持断点续传，以决定是否开启多线程下载文件的不同部分
	public boolean supportResumeDownload() throws IOException {
		HttpURLConnection con = (HttpURLConnection) url.openConnection();
		con.setRequestProperty("Range", "bytes=0-");
		int resCode;
		while (true) {
			try {
				con.connect();
				fileSize = con.getContentLength();
				resCode = con.getResponseCode();
				con.disconnect();
				break;
			} catch (ConnectException e) {
				System.out.println("Retry to connect due to connection problem.");
			}
		}
		if (resCode == 206) {
			System.out.println("* Support resume download");
			return true;
		} else {
			System.out.println("* Doesn't support resume download");
			return false;
		}
	}

	//监测下载速度及下载状态，下载完成时通知主线程
	public void startDownloadMonitor() {
		Thread downloadMonitor = new Thread(() -> {
			int prev = 0;
			int curr = 0;
			while (true) {
				try {
					Thread.sleep(1000);
				} catch (InterruptedException e) {}

				curr = downloadedBytes.get();
				System.out.println(String.format("Speed: %d KB/s, Downloaded: %d KB (%.2f%%), Threads: %d",
						(curr - prev) >> 10, curr >> 10, curr / (float) fileSize * 100, aliveThreads.get()));
				prev = curr;

				if (aliveThreads.get() == 0) {
					synchronized (waiting) {
						waiting.notifyAll();
					}
				}
			}
		});

		downloadMonitor.setDaemon(true);
		downloadMonitor.start();
	}

	//对临时文件进行合并或重命名
	public void cleanTempFile() throws IOException {
		if (multithreaded) {
			merge();
			System.out.println("* Temp file merged.");
		} else {
			Files.move(Paths.get(localFile.getAbsolutePath() + ".0.tmp"),
					Paths.get(localFile.getAbsolutePath()), StandardCopyOption.REPLACE_EXISTING);
		}
	}

	//合并多线程下载产生的多个临时文件
	public void merge() {
		try (OutputStream out = new FileOutputStream(localFile)) {
			byte[] buffer = new byte[1024];
			int size;
			for (int i = 0; i < THREAD_NUM; i++) {
				String tmpFile = localFile.getAbsolutePath() + "." + i + ".tmp";
				InputStream in = new FileInputStream(tmpFile);
				while ((size = in.read(buffer)) != -1) {
					out.write(buffer, 0, size);
				}
				in.close();
				Files.delete(Paths.get(tmpFile));
			}
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	//一个下载线程负责下载文件的某一部分，如果失败则自动重试，直到下载完成
	class DownloadThread extends Thread {
		private int id;
		private int start;
		private int end;
		private OutputStream out;

		public DownloadThread(int id, int start, int end) {
			this.id = id;
			this.start = start;
			this.end = end;
			aliveThreads.incrementAndGet();
		}

        //保证文件的该部分数据下载完成
		@Override
		public void run() {
			boolean success = false;
			while (true) {
				success = download();
				if (success) {
					System.out.println("* Downloaded part " + (id + 1));
					break;
				} else {
					System.out.println("Retry to download part " + (id + 1));
				}
			}
			aliveThreads.decrementAndGet();
		}

        //下载文件指定范围的部分
		public boolean download() {
			try {
				HttpURLConnection con = (HttpURLConnection) url.openConnection();
				con.setRequestProperty("Range", String.format("bytes=%d-%d", start, end));
				con.setConnectTimeout(TIME_OUT);
				con.setReadTimeout(TIME_OUT);
				con.connect();
				int partSize = con.getHeaderFieldInt("Content-Length", -1);
				if (partSize != end - start + 1) return false;
				if (out == null) out = new FileOutputStream(localFile.getAbsolutePath() + "." + id + ".tmp");
				try (InputStream in = con.getInputStream()) {
					byte[] buffer = new byte[1024];
					int size;
					while (start <= end && (size = in.read(buffer)) > 0) {
						start += size;
						downloadedBytes.addAndGet(size);
						out.write(buffer, 0, size);
						out.flush();
					}
					con.disconnect();
					if (start <= end) return false;
					else out.close();
				}
			} catch(SocketTimeoutException e) {
				System.out.println("Part " + (id + 1) + " Reading timeout.");
				return false;
			} catch (IOException e) {
				System.out.println("Part " + (id + 1) + " encountered error.");
				return false;
			}

			return true;
		}
	}

}
