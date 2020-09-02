package net.scheffers.robot.simplexfs;

import jutils.IOUtils;
import jutils.database.DataPacker;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.LinkedList;
import java.util.List;

public class SimplexFS {
	
	public static boolean doSendWarnings = true;
	
	/**
	 * Makes a SimplexFS with the given parameters.
	 * Between 16 and 65535 sectors of 256 bytes.
	 * @param output the file to write to
	 * @param volumeName the volume name (optional)
	 * @param numSectors the number of sectors in total
	 * @param mediaType the media type (default 0x00)
	 * @param identifier the identifier (any)
	 * @param include directory to recursively include as root (optional)
	 * @throws IOException when reading or writing fails
	 * @throws IllegalArgumentException when the number of sectors is out of range
	 * @throws IllegalArgumentException when volume name is too long
	 * @throws Exception if there is not enough space to include the requested files
	 */
	public static void makeSimplexFS(File output, String volumeName, int numSectors, byte mediaType, int identifier, File include) throws Exception {
		FileOutputStream out = new FileOutputStream(output);
		
		// Sanity check.
		if (numSectors > 65535) {
			throw new IllegalArgumentException("SimplexFS cannot hold more than 65535 sectors!");
		}
		if (numSectors < 5) {
			throw new IllegalArgumentException("SimplexFS cannot hold less than 5 sectors!");
		}
		else if (numSectors < 16) {
			warn("Less then 16 sectors is highly discouraged!");
		}
		if (volumeName == null || volumeName.length() == 0) {
			volumeName = "nameless drive";
		}
		else
		{
			boolean gotNull = false;
			boolean gotCtrl = false;
			boolean gotNonAscii = false;
			
			for (char c : volumeName.toCharArray()) {
				if (c == 0) {
					gotNull = true;
				} else if (c < 0x20) {
					gotCtrl = true;
				} else if (c > 0x7f) {
					gotNonAscii = true;
				}
			}
			
			if (gotNull) warn("Got null character in volume name!");
			if (gotCtrl) warn("Got ascii control character in volume name!");
			if (gotNonAscii) warn("Got non-ascii character in volume name!");
			if (gotNonAscii || gotCtrl || gotNull) {
				warn("For volume name \"" + escVoln(volumeName) + "\"");
			}
			if (volumeName.getBytes(StandardCharsets.US_ASCII).length > 24) {
				throw new IllegalArgumentException("Volume name cannot be longer than 24 ascii bytes!");
			}
		}
		
		int fatSectorSize = (numSectors + 127) / 128;
		int rootDirStart = 2 + fatSectorSize * 2;
		
		byte[][] sectors = new byte[numSectors][];
		
		byte[] header = new byte[256];
		
		// Header magic.
		header[0] = (byte) 0xfe;
		header[1] = (byte) 0xca;
		header[2] = (byte) 0x01;
		header[3] = (byte) 0x32;
		header[4] = (byte) 0x94;
		// Number of sectors.
		header[5] = (byte) (numSectors & 0xff);
		header[6] = (byte) (numSectors >>> 8);
		// Number of FAT entries.
		header[7] = (byte) (numSectors & 0xff);
		header[8] = (byte) (numSectors >>> 8);
		// Number of sectors FAT uses.
		header[9] = (byte) (fatSectorSize & 0xff);
		header[10] = (byte) (fatSectorSize >>> 8);
		// Root directory sector.
		header[11] = (byte) (rootDirStart & 0xff);
		header[12] = (byte) (rootDirStart >>> 8);
		// Filesystem version (v1.0).
		header[13] = (byte) 1;
		header[14] = (byte) 0;
		// Media type.
		header[15] = mediaType;
		// Identifier.
		header[16] = (byte) ((identifier >>> 24) & 0xff);
		header[17] = (byte) ((identifier >>> 16) & 0xff);
		header[18] = (byte) ((identifier >>> 8) & 0xff);
		header[19] = (byte) (identifier & 0xff);
		// Volume name.
		byte[] volName = volumeName.getBytes(StandardCharsets.US_ASCII);
		System.arraycopy(volName, 0, header, 20, volName.length);
		
		// Now, we need to make the allocation tables before we can continue.
		byte[] fat = new byte[fatSectorSize * 256];
		// Mark out the reserved sectors.
		for (int i = 0; i < rootDirStart; i++) {
			fat[i * 2] = (byte) 0xff;
			fat[i * 2 + 1] = (byte) 0xff;
		}
		
		if (include == null) {
			// Create dummy root.
			fat[rootDirStart * 2] = (byte) 0xff;
			fat[rootDirStart * 2 + 1] = (byte) 0xff;
			// Funnily enough, completely null is a valid empty directory.
		}
		else
		{
			// Time for some recursion.
			InsertedFile rootStart = insertFile(include, header, fat, sectors);
		}
		
		// Calculate FAT checksum.
		byte fatCksumLo = 0;
		byte fatCksumHi = 0;
		for (int i = 0; i < numSectors; i++) {
			fatCksumLo ^= fat[i * 2];
			fatCksumHi ^= fat[i * 2 + 1];
		}
		
		// Fat checksum.
		header[252] = fatCksumLo;
		header[253] = fatCksumHi;
		
		// Calculate header checksum.
		byte headCksumLo = 0;
		byte headCksumHi = 0;
		for (int i = 0; i < 254; i += 2) {
			headCksumLo ^= header[i];
			headCksumHi ^= header[i + 1];
		}
		
		// Header checksum.
		header[254] = headCksumLo;
		header[255] = headCksumHi;
		
	}
	
	/**
	 * Inserts a file or directory directory from the host system into SimplexFS.
	 * Does not recalculate any checksums.
	 * @param include file to inlude
	 * @param header filesystem header
	 * @param fat fat to use and allocate to
	 * @param sectors an array of sectors, full size even in excluding header and fat
	 * @return the starting sector of the new file or directory
	 * @throws Exception if the filesystem is too full to add this file or directory
	 */
	public static InsertedFile insertFile(File include, byte[] header, byte[] fat, byte[][] sectors) throws Exception {
		byte[] data;
		if (include.isDirectory()) {
			data = includeDirectory(include, header, fat, sectors);
		}
		else
		{
			data = IOUtils.readBytes(include);
		}
		InsertedFile ins = new InsertedFile();
		ins.fileSize = data.length;
		List<Integer> chain = new LinkedList<>();
		int numBlocks = (data.length + 255) / 256;
		for (int i = 0; i < numBlocks; i++) {
			// TODO: find a chain
		}
		ins.startingBlock = chain.get(0);
		return ins;
	}
	
	/**
	 * Directory part of {@link SimplexFS#insertFile(File, byte[], byte[], byte[][])}
	 */
	protected static byte[] includeDirectory(File include, byte[] header, byte[] fat, byte[][] sectors) throws Exception {
		File[] sub = include.listFiles();
		int stringLen = 0;
		for (File f : sub) {
			stringLen += f.getName().getBytes(StandardCharsets.US_ASCII).length + 1;
		}
		byte[] data = new byte[4 + sub.length * 16 + stringLen];
		int stringIndex = 4 + sub.length * 16;
		int fileIndex = 2;
		for (File f : sub) {
			//                            dir      file
			//                          0q040644 0q000644
			int flags = f.isDirectory() ? 0x41a4 : 0x01a4;
			data[fileIndex] = (byte) (flags & 0xff);
			data[fileIndex + 1] = (byte) (flags >>> 8);
			// File owner.
			data[fileIndex + 2] = (byte) 0x00;
			data[fileIndex + 3] = (byte) 0x00;
			InsertedFile ins = insertFile(f, header, fat, sectors);
			// File starting block.
			data[fileIndex + 4] = (byte) (ins.startingBlock & 0xff);
			data[fileIndex + 5] = (byte) (ins.startingBlock >>> 8);
			// File length.
			data[fileIndex + 6] = (byte) (ins.fileSize & 0xff);
			data[fileIndex + 7] = (byte) (ins.fileSize >>> 8);
			data[fileIndex + 8] = (byte) (ins.fileSize >>> 16);
			// Checksum of content.
			data[fileIndex + 9] = ins.checkSumLo;
			data[fileIndex + 10] = ins.checkSumHi;
			// Filename pointer.
			data[fileIndex + 11] = (byte) (stringLen & 0xff);
			data[fileIndex + 12] = (byte) (stringLen >>> 8);
			byte[] str = f.getName().getBytes(StandardCharsets.US_ASCII);
		}
		return data;
	}
	
	public static String escVoln(String volumeName) {
		StringBuilder out = new StringBuilder();
		for (char c : volumeName.toCharArray()) {
			if (c == 0) {
				out.append("\\0");
			}
			else if (c < 0x20) {
				out.append(String.format("\\x%02x", (byte) c));
			}
			else if (Character.isWhitespace(c) && c != ' ') {
				if (c > 0xff) {
					out.append(String.format("\\u%04x", (int) c));
				}
				else
				{
					out.append(String.format("\\x%02x", (byte) c));
				}
			}
			else
			{
				out.append(c);
			}
		}
		return out.toString();
	}
	
	public static void warn(String message) {
		if (doSendWarnings) {
			System.err.println("[SimplexFS] [WARN] " + message);
		}
	}
	
}
