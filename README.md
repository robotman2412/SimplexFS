# SimplexFS
Simple filesystem for embedded devices.

SimplexFS is designed to be a simple-as-possible filesystem for embedded devices or otherwise devices with very little resources.

# format

First, simplex is seperated into up to 65536 sectors of 256 bytes each.
All numbers are stored in little-endian.

## filesystem header
The first sector always contains a header, as follows:

| OFFSET | LENGTH | NAME            | DESCRIPTION |
| :----- | :----- | :-------------- | :---------- |
| 0      | 5      | sxh_magic       | Magic, always 0xfe 0xca 0x01 0x32 0x94. |
| 5      | 2      | sxh_nsect       | Number of sectors in total. This is the number of sectors which fit in the volume or partition. This number includes the sectors required for the header and allocation table, and their copies. |
| 7      | 2      | sxh_fat_entries | Allocation unit size in entries of 16 bits. |
| 9      | 2      | sxh_fat_sectors | Allocation unit size in sectors. |
| 11     | 2      | sxh_root        | Sector index of root directory. |
| 13     | 2      | sxh_ver         | Version ID of the filesystem; this specification is for version ID 0x01 0x00. First byte is major version, second byte is minor version. |
| 15     | 1      | sxh_media       | Media type: denotes how the media is structured. See: media types table below. |
| 16     | 4      | sxh_id          | Identifier for host OS: any data. |
| 20     | 24     | sxh_volid       | Volume name: ascii text, null-terminated if shorter than 24 charatcers. |
| 44     | 3      | sxh_rootlen     | Length in bytes of the root directory. |
| 252    | 2      | sxh_fat_chksum  | Allocation unit checksum: checksum of the allocation unit. Functions the same as the header checksum. |
| 254    | 2      | sxh_chksum      | Header checksum, even bytes are XORed into 254, while odd bytes are XORed into 255. For obvious reasons, bytes 254 and 255 are excluded from this logic. |

To check that the filesystem header is not corrupt, do the following:
```
assert sxh_magic == 0xfeca0132
assert (sxh_fat_entries + 127) >> 7 == sxh_fat_sectors
checksum_even = 0
checksum_odd = 0
for (i in 0 to 254 step 2) {
    checksum_even = checksum_even ^ header_raw_bytes[i]
    checksum_odd = checksum_odd ^ header_raw_bytes[i + 1]
}
assert checksum_even == sxh_chksum & 0xff # (low byte, offset 254)
assert checksum_odd == sxh_chksum >> 8 # (high byte, offset 255)
```

In the case that this is corrupt, there is a failsafe: there is two copies of the filesystem header and two copies of the allocation unit.
This allows file recovery utilities to help even if one of the copies breaks.

## media types
Media types are a hint to what type the media is and can be used to tell the host OS how to read.
Media types are not necessarily any of these values.

| TYPE | DESCRIPTION |
| :--- | :---------- |
| 0x0? | Generic; can be any media and simple follows the 256 byte sector rule. |
| 0x1? | Removable; generic for removable media types. |
| 0x2? | Generic read-only; generic for read-only media. |
| 0x3? | Removable read-only; generic for read-only removable media. |
| 0x?0 | Simple media with 256 byte physical and logical sectors. |
| 0x?1 | Simple media with 512 byte physical sectors. Logical sectors remain 256 bytes. |
| 0x?2 | Simple media with 1024 byte physical sectors. Logical sectors remain 256 bytes. |
| 0x?3 | Simple media with 2048 byte physical sectors. Logical sectors remain 256 bytes. |
| 0x?4 | Simple media with 4096 byte physical sectors. Logical sectors remain 256 bytes. |


Now that you can be sure that the filesystem is not corrupt (or present in the first place), you want to know the allocation unit format.
The allocation unit lives directly after the header sector and it's backup (so starting at sector 2).
The allocation unit also has a copy directly after it.
The allocation unit and has the following format:

## allocation unit
The allocation unit is an array of 16-bit values which depict what is used and how sectors are connected.

| START  | END    | DESCRIPTION |
| :----- | :----- | :---------- |
| 0x0000 | 0x0000 | Free sector; nothing of importance is present. |
| 0x0001 | 0xfffe | Allocated sector; this value points to the next sector. |
| 0xffff | 0xffff | Allocated sector; this value indicates there is no next sector. |

The sector ID represents sectors on the media.
This means the first few sector IDs are all 0xffff so as to not allocate reserved sectors.

To determine the free space, iterate over all sectors in the allocation unit and count the number of 0x0000 sectors.


## directory structure


| OFFSET             | LENGTH           | NAME         | DESCRIPTION |
| :----------------- | :--------------- | :----------- | :---------- |
| 0                  | 2				| dir_files    | Number of files in the directory. |
| 2                  | dir_files * 32	| dir_entries  | A list of file entries: one per file in the directory. |

The filename strings correspond to the file entries in the order they are found in, the first file entry corresponds to the first filename.

### file entry
Found within the directory, a file entry is used to find a file and infomation about it.
| OFFSET | LENGTH | NAME      | DESCRIPTION |
| :----- | :----- | :-------- | :---------- |
| 0      | 2      | fe_flags  | File flags: see flags table below. |
| 2      | 2      | fe_uid    | User ID: any data the host OS needs to identify users. |
| 4      | 2      | fe_sect   | Starting sector of the file: first sector of the file's data. |
| 6      | 3      | fe_len    | File length; low byte indicates how much of the last block is in use. |
| 9      | 2      | fe_chksum | Checksum of the file's contents: can be used to check validity. |
| 11     | 5      | reserved  | Unused; set to 0x00. |
| 16     | 16     | fe_name   | Name of the file; null-terminated ASCII string. |

### file flags
These flags are equal to the linux mode flags (man 2 chmod).
| BIT   | OCTAL    | HEX    | DESCRIPTION |
| :---- | :------- | :----- | :---------- |
| 0     | 0q000001 | 0x0001 | Guest exec premission: 1 if a non-owner user may execute the file. |
| 1     | 0q000002 | 0x0002 | Guest edit premission: 1 if a non-owner user may edit or delete the file. |
| 2     | 0q000004 | 0x0004 | Guest read premission: 1 if a non-owner user may read the file. |
| 3     | 0q000010 | 0x0008 | Group exec premission: 1 if the owner's group may execute the file. |
| 4     | 0q000020 | 0x0010 | Group edit premission: 1 if the owner's group may edit or delete the file. |
| 5     | 0q000040 | 0x0020 | Group read premission: 1 if the owner's group may read the file. |
| 6     | 0q000100 | 0x0040 | Owner exec premission: 1 if the owner may execute the file. |
| 7     | 0q000200 | 0x0080 | Owner edit premission: 1 if the owner may edit or delete the file. |
| 8     | 0q000400 | 0x0100 | Owner read premission: 1 if the owner may read the file. |
| 9     | 0q001000 | 0x0200 | Sticky bit; restricted deletion flag, as described in (man 2 unlink).
| 10    | 0q002000 | 0x0400 | Set group ID: 1 if the file, upon executed, must use the owner's group ID instead of the executor's group ID. |
| 11    | 0q004000 | 0x0800 | Set user ID: 1 if the file, upon executed, must use the owner's user ID instead of the executor's user ID. |
| 12-13 | 0q030000 | 0x3000 | Unused; set to 0.
| 14    | 0q040000 | 0x4000 | Is directory: 1 if the file is a directory. Do NOT edit with CHMOD. |
| 15    | 0q100000 | 0x8000 | Is system: 1 if the file or directory is required by the system. |



