"""Adapt FBNeo to the two Fightcade program revisions verified on desktop."""

import argparse
import hashlib
from pathlib import Path
import struct


SOURCE_SHA256 = "5a15ab447444632d2821c440d4e3af17f1794b6c9cfeee0fbec1f1c05e34aba5"
RESULT_SHA256 = "a9db9b2ce5eeb85c07f4d34d22da63eba9cfffa929c279ae6c440de0392d21b4"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    data = bytearray(args.source.read_bytes())
    if hashlib.sha256(data).hexdigest() != SOURCE_SHA256:
        parser.error("Source FBNeo checksum does not match")

    last_bullet = data.index(struct.pack("<I", 0x94527837))
    new_battle = data.index(struct.pack("<I", 0x4DF30BCB))
    if struct.unpack_from("<I", data, last_bullet + 16)[0] != 0x08EE1FE3:
        parser.error("Last Bullet second program descriptor does not match")
    struct.pack_into("<I", data, last_bullet, 0xF804B927)
    struct.pack_into("<I", data, last_bullet + 16, 0x0AF11B49)
    struct.pack_into("<I", data, new_battle, 0xB0C126DA)
    if hashlib.sha256(data).hexdigest() != RESULT_SHA256:
        parser.error("Output FBNeo checksum does not match")
    args.output.write_bytes(data)


if __name__ == "__main__":
    main()
