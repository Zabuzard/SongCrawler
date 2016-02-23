package de.zabuza.songcrawler;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Runnable class that extracts the download
 * link of song by using a web interface.
 * 
 * @author Zabuza
 */
public final class SongDownload implements Runnable {
	
	/**
	 * Mask where albums starts in the web.
	 */
	private static final String SONG_MASK_START = "<p align=\"left\">Go back to the album";
	/**
	 * Mask where albums ends in the web.
	 */
	private static final String SONG_MASK_END = "</audio>";
	/**
	 * Mask that separates song name and album name.
	 */
	private static final String SONG_INFO_SEPARATOR = "/";
	/**
	 * Path to song in web.
	 */
	private final String path;
	/**
	 * Songcrawler that uses this object.
	 */
	private final Songcrawler crawler;
	
	public SongDownload(Songcrawler thatCrawler, String thatPath) {
		this.crawler = thatCrawler;
		this.path = thatPath;
	}

	@Override
	public void run() {
		String[] songDownload = getSongUrl(path);
		crawler.addSong(songDownload);
	}
	
	/**
	 * Gets the download url, name of the song and name of songs album, to a song from its download page.
	 * @param path Path to the song download page
	 * @return [0] = Download url of the song, [1] = Name of the song, [2] = Name of songs album
	 * @throws IOException If an I/O-Exception occurs
	 */
	private static String[] getSongUrl(String path) {
		String[] song = new String[3];
		song[0] = "";
		song[1] = "";
		song[2] = "";
		List<String> content = new ArrayList<String>(0);
		try {
			content = Songcrawler.getWebContent(path);
		} catch (IOException e) {
			System.err.println("Unknown error while reading web ressources of song ('"
					+ path + "'), contact developer.");
			e.printStackTrace();
		}
		
		//Reject everything before the masks
		int i = -1;
		String line = "";
		do {
			i++;
			line = content.get(i);
		} while (!line.contains(SONG_MASK_START));
		
		int loopCounter = 0;
		do {
			i++;
			line = content.get(i);
			Pattern pattern = Pattern.compile("<p><a style=\"color:[\\s]?#[A-Fa-f0-9]{6};\" href=\"(http[s]?://.+\\.mp3)\">Click here to download</a>",
					Pattern.CASE_INSENSITIVE);
			Matcher matcher = pattern.matcher(line);
			if (matcher.find()) {
				loopCounter = 0;
				String url = matcher.group(1);
				song[0] = url;
			}
			loopCounter++;
		} while (!line.contains(SONG_MASK_END) && loopCounter < Songcrawler.LOOP_ABORT);
		
		if (song[0].equals("")) {
			System.err.println("Could not find the download link for song: '" + path + "', contact developer.");
		}
		if (loopCounter >= Songcrawler.LOOP_ABORT) {
			System.err.println("Search for download link for song('" + path
					+ "') was aborted: Too many lines without progress, contact developer.");
		}
		
		int startOfSongName = song[0].lastIndexOf(SONG_INFO_SEPARATOR);
		if (startOfSongName != -1) {
			song[1] = song[0].substring(startOfSongName + SONG_INFO_SEPARATOR.length(),
					song[0].length());
		}
		int startOfTempName = song[0].lastIndexOf(SONG_INFO_SEPARATOR, startOfSongName - 1);
		int startOfAlbumName = song[0].lastIndexOf(SONG_INFO_SEPARATOR, startOfTempName - 1);
		if (startOfAlbumName != -1 && startOfSongName != -1) {
			song[2] = song[0].substring(startOfAlbumName + SONG_INFO_SEPARATOR.length(), startOfTempName);
		}
		
		if (song[1].equals("") || song[2].equals("")) {
			System.err.println("Could not extract name of song or album out of songs download link: '" + path + "', contact developer.");
		}
		
		return song;
	}
}
