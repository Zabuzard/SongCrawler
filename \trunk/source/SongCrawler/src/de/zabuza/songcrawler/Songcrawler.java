package de.zabuza.songcrawler;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Utility class.
 * Provides a web crawler that searches for some
 * song download links on a web page.
 * 
 * @author Zabuza
 */
public final class Songcrawler {
	/**
	 * Path to the directory where output should be placed.
	 */
	private static final String FILEPATH_OUTPUT = System.getProperty("user.dir");
	/**
	 * Path to the file that contains the output of the program.
	 */
	private static final String FILEPATH_OUTPUT_FILE = FILEPATH_OUTPUT + "\\songs.txt";;
	/**
	 * Path to the server.
	 */
	private static final String SERVERPATH = "http://downloads.khinsider.com/";
	/**
	 * Path to the music site.
	 */
	private static final String MUSIC_PATH = SERVERPATH + "game-soundtracks/browse/all";
	/**
	 * Mask 1 where albums starts in the web.
	 */
	private static final String ALBUMS_MASK_START1 = "<h2>Albums</h2>";
	/**
	 * Mask 2 where albums starts in the web.
	 */
	private static final String ALBUMS_MASK_START2 = "<p align=\"left\">";
	/**
	 * Mask where albums ends in the web.
	 */
	private static final String ALBUMS_MASK_END = "</p>";
	/**
	 * Mask where albums starts in the web.
	 */
	private static final String SONGS_MASK_START = "<td bgcolor=\"#CCCC99\"><b>Download</b></td>";
	/**
	 * Mask where albums ends in the web.
	 */
	private static final String SONGS_MASK_END = "</table>";
	/**
	 * Constant for runs, without progress, after which a loop should be aborted.
	 */
	public static final int LOOP_ABORT = 1000;
	/**
	 * Maximal time in minutes the thread pool waits for threads to finish.
	 */
	private static final int THREAD_TIMEOUT_MINUTES = 30;
	/**
	 * Amount of worked albums after which a saving for safety occurs.
	 */
	private static final int SAFETY_SAVING = 100;
	
	private final List<String[]> songs;
	private final Lock lock;
	
	
	public Songcrawler() {
		songs = new LinkedList<String[]>();
		lock = new ReentrantLock();
	}
	
	/**
	 * Crawls a web page and searches for song download links.
	 * @param startAlbum Index of album at which crawling should
	 * start which must be greater than 1
	 */
	public void crawlWeb(int startAlbum) {
		System.out.println(">Start crawling download links from '" + MUSIC_PATH + "' ...");
		List<String> albums = new ArrayList<String>(0);
		try {
			albums = getAlbumUrls(MUSIC_PATH);
			int albumsSize = albums.size();
			System.out.println(">Found albums: " + albumsSize);
			
			long startTime = Calendar.getInstance().getTime().getTime();
			int workedAlbums = startAlbum - 1;
			for (int i = startAlbum - 1; i < albumsSize; i++) {
				//Crawl album
				String album = albums.get(i);
				List<String> albumSongs = crawlAlbum(album);
				
				//Crawl song web sites using threads and add them to list 'songs'
				ExecutorService executor = Executors.newFixedThreadPool(albumsSize);
				for (String songWebsite : albumSongs) {
					executor.execute(new SongDownload(this, songWebsite));
				}
				executor.shutdown();
				try {
					executor.awaitTermination(THREAD_TIMEOUT_MINUTES, TimeUnit.MINUTES);
				} catch (InterruptedException e) {
					System.err.println("Unknown error while waiting for threads to end, contact developer.");
					e.printStackTrace();
				}
				//Finished crawling album
				workedAlbums++;
				
				//Output some information for user
				if (workedAlbums % 2 == 0) {
					long curTime = Calendar.getInstance().getTime().getTime();
					double diffInMin = ((double) (curTime - startTime)) / (1000 * 60);
					double estimatedDurationMin = (diffInMin / (workedAlbums - (startAlbum - 1)))
							* (albumsSize - (startAlbum - 1));
					
					double estimatedMins = estimatedDurationMin - diffInMin;
					double estimatedHours = estimatedMins / 60;
					double estimatedSeconds = Double.parseDouble("0" + (estimatedMins
							+ "").substring((estimatedMins + "").indexOf('.'))) * 60;
					estimatedMins %= 60;
					int estimatedMinsRounded = (int) Math.floor(estimatedMins);
					int estimatedHoursRounded = (int) Math.floor(estimatedHours);
					int estimatedSecondsRounded = (int) Math.floor(estimatedSeconds);
					
					int estimatedSongs = (int) Math.round((((double) songs.size()) / (workedAlbums - (startAlbum - 1)))
							* (albumsSize - (startAlbum - 1)));
					
					System.out.println(">albums: " + workedAlbums + " (" + albumsSize + "), songs: "
							+ songs.size() + " (est. " + estimatedSongs
							+ "), est. time rem.: " + estimatedHoursRounded + "h " + estimatedMinsRounded
							+ "m " + estimatedSecondsRounded + "s");
				}
				
				//Process data for safety
				if (workedAlbums % SAFETY_SAVING == 0) {
					processSongList(songs);
				}
			}
		} catch (IOException e) {
			System.err.println("Unknown error while reading web ressources, contact developer.");
			e.printStackTrace();
		}
		System.out.println(">Found albums: " + albums.size());
		System.out.println(">Found songs: " + songs.size());
		System.out.println(">End crawling download links...");
		
		//Process the results
		processSongList(songs);
		System.out.println(">Program terminated.");
	}
	/**
	 * Adds a song info to the song list.
	 * Method is thread-safety.
	 * @param songInfo [0] = Download url of the song, [1] = Name of the song, [2] = Name of songs album
	 */
	public void addSong(String songInfo[]) {
		lock.lock();
		songs.add(songInfo);
		lock.unlock();
	}
	
	/**
	 * Processes a list of songs.
	 * 
	 * @param list List of songs
	 */
	private void processSongList(List<String[]> list) {
		saveListIntoFile(list, FILEPATH_OUTPUT_FILE);
		sortSongListOnDrive(list, FILEPATH_OUTPUT);
	}
	
	/**
	 * Sorts a list of songs on the drive.
	 * Checks if a song file exists and moves it in its album folder.
	 * @param list List of songs to sort
	 * @param path path to the song files
	 */
	private void sortSongListOnDrive(List<String[]> list, String path) {
		System.out.println(">Start sorting song files at '" + path + "' ...");
		
		for (String[] songInfo : list) {
			File songFile = new File(path, songInfo[1]);
			
			// Check if there is a file that must be sorted in
			if (songFile.exists() && !songFile.isDirectory()) {
				File albumDir = new File(path, songInfo[2]);
				
				// If there is no such album directory create one
				if (!(albumDir.exists() && albumDir.isDirectory())) {
					if (!albumDir.mkdir()) {
						System.err.println("Could not create album folder at: '" + albumDir.getPath() + "', contact developer.");
					}
				}
				
				// Now move file into the folder
				File destFile = new File(albumDir, songInfo[1]);
				try {
					Files.move(songFile.toPath(), destFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
				} catch (IOException e) {
					System.err.println("Unknown error while moving a file to '" + destFile.toPath() + "', contact developer.");
					e.printStackTrace();
				}
			}
		}
		
		System.out.println(">End sorting...");
	}
	
	/**
	 * Saves a list of lines into a file.
	 * @param list List of lines to save
	 * @param path path to the file
	 */
	private void saveListIntoFile(List<String[]> list, String path) {
		System.out.println(">Start saving into '" + path + "' ...");
		HashSet<String> workedAlbums = new HashSet<String>();
		
		BufferedWriter wr = null;
		try {
			wr = new BufferedWriter(new FileWriter(path));
			for (String[] line : list) {
				if (workedAlbums.add(line[2])) {
					// First song of a new album
					wr.newLine();
					wr.write("Album: " + line[2]);
					wr.newLine();
				}
				wr.write(line[0]);
				wr.newLine();
			}
		} catch (IOException e) {
			System.err.println("Unknown error while writing into '" + path + "', contact developer.");
			e.printStackTrace();
		} finally {
			try {
				if (wr != null) {
					wr.close();
				}
			} catch (IOException e) {
				System.err.println("Unknown error while closing '" + path + "', contact developer.");
				e.printStackTrace();
			}
		}
		System.out.println(">End saving...");
	}
	
	/**
	 * Gets the urls to all albums by using the web path.
	 * @param path Path to the album list
	 * @return List of urls to all albums
	 * @throws IOException If an I/O-Exception occurs
	 */
	private List<String> getAlbumUrls(String path) throws IOException {
		List<String> albums = new ArrayList<String>(9000);
		List<String> content = getWebContent(path);
		
		//Reject everything before the masks
		int i = -1;
		String line = "";
		do {
			i++;
			line = content.get(i);
		} while (!line.contains(ALBUMS_MASK_START1));
		do {
			i++;
			line = content.get(i);
		} while (!line.contains(ALBUMS_MASK_START2));
		
		int loopCounter = 0;
		do {
			i++;
			line = content.get(i);
		
			Pattern pattern = Pattern.compile("<a href=\"(http[s]?://.+)\">.+</a><br>",
					Pattern.CASE_INSENSITIVE);
			Matcher matcher = pattern.matcher(line);
			if (matcher.find()) {
				loopCounter = 0;
				String url = matcher.group(1);
				albums.add(url);
			}
			loopCounter++;
		} while (!line.contains(ALBUMS_MASK_END) && loopCounter < LOOP_ABORT);
		
		if (albums.size() == 0) {
			System.err.println("Could not find any album.");
		}
		if (loopCounter >= LOOP_ABORT) {
			System.err.println("Search for albums was aborted: Too many lines without progress, contact developer.");
		}
		
		return albums;
	}
	
	/**
	 * Gets the urls to all songs of an album by using the web path.
	 * @param path Path to the album
	 * @return List of urls to all songs of this album
	 * @throws IOException If an I/O-Exception occurs
	 */
	private List<String> crawlAlbum(String path) throws IOException {
		List<String> songs = new LinkedList<String>();
		List<String> content = getWebContent(path);
		
		//Reject everything before the masks
		int i = -1;
		String line = "";
		do {
			i++;
			line = content.get(i);
		} while (!line.contains(SONGS_MASK_START));
		
		int loopCounter = 0;
		do {
			i++;
			line = content.get(i);
		
			Pattern pattern = Pattern.compile("<td><a href=\"(http[s]?://.+\\.mp3)\">Download</a></td>",
					Pattern.CASE_INSENSITIVE);
			Matcher matcher = pattern.matcher(line);
			if (matcher.find()) {
				loopCounter = 0;
				String url = matcher.group(1);
				songs.add(url);
			}
			loopCounter++;
		} while (!line.contains(SONGS_MASK_END) && loopCounter < LOOP_ABORT);
		
		if (songs.size() == 0) {
			System.err.println("Could not find any song for the album: '" + path + "', contact developer.");
		}
		if (loopCounter >= LOOP_ABORT) {
			System.err.println("Search for songs for this album('" + path
					+ "') was aborted: Too many lines without progress, contact developer.");
		}
		
		return songs;
	}
	
	/**
	 * Gets the content of a web page and returns it as list of lines.
	 * @param path Path to the web page
	 * @return List of lines from the content
	 * @throws IOException If an I/O-Exception occurs
	 */
	public static List<String> getWebContent(String path) throws IOException {
		URL url = new URL(path);
		BufferedReader site = new BufferedReader(new InputStreamReader(url.openStream()));
		List<String> content = new ArrayList<String>();
		
		String line = site.readLine();
		while (line != null){
			content.add(line);
			line = site.readLine();
		}
		
		site.close();
		return content;
	}
	
	/**
	 * 
	 * @param args
	 *            Not supported
	 */
	public static void main(final String[] args) {
		int startAlbum = 1;
		if (args.length > 0) {
			try {
				startAlbum = Integer.parseInt(args[0]);
				if (startAlbum < 1) {
					startAlbum = 1;
					throw new NumberFormatException();
				}
			} catch (NumberFormatException e) {
				System.err.println("Custom argument must be an integer greater than 1.");
			}
		}
		Songcrawler crawler = new Songcrawler();
		
		/*
		 * If true the program will use the web-crawler to create
		 * a list of song links. If false the program simply
		 * will do nothing. This is a safety protection.
		 * 
		 * ********************** CAUTION **********************
		 * Crawling, of course, causes a heavy amount of web-traffic.
		 * If you use this frequently the server can interpret this as DDos!
		 * This could lead to legal consequences for the executor
		 * of this program!
		 * So use "true" only if you know what you do.
		 * ********************** CAUTION **********************
		 */
		boolean crawlWeb = false;
		if (crawlWeb) {
			crawler.crawlWeb(startAlbum);
		}
	}

}
