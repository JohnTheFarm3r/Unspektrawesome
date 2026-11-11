import argparse
import re
import struct
import zipfile
from pathlib import Path


def unpack(data, fmt, offset):
    return struct.unpack_from("<" + fmt, data, offset)


def cstring(data, offset):
    return data[offset:data.index(b"\0", offset)].decode("utf-8")


def check_library(data, package, certificate):
    assert data[:6] == b"\x7fELF\x02\x01", "Expected a little-endian ELF64 library"
    section_offset = unpack(data, "Q", 40)[0]
    section_size, section_count, names_index = unpack(data, "HHH", 58)
    sections = [unpack(data, "IIQQQQIIQQ", section_offset + index * section_size)
                for index in range(section_count)]
    names = sections[names_index]
    exports = set()
    for section in sections:
        name = cstring(data, names[4] + section[0])
        assert name != ".symtab" and not name.startswith(".debug"), f"Debug symbols remain: {name}"
        if section[1] != 11:
            continue
        strings = sections[section[6]]
        for offset in range(section[4], section[4] + section[5], section[9]):
            symbol, info, visibility, defined, _, _ = unpack(data, "IBBHQQ", offset)
            if defined and info >> 4 in (1, 2) and visibility & 3 == 0:
                exports.add(cstring(data, strings[4] + symbol))
    assert exports == {"JNI_OnLoad"}, f"Unexpected native exports: {exports}"
    assert package.encode() + b"\0" in data, "Expected host package is missing"
    assert certificate in data, "Expected signing certificate fingerprint is missing"


def uleb(data, offset):
    value, shift = 0, 0
    while True:
        byte = data[offset]
        offset += 1
        value |= (byte & 127) << shift
        if byte < 128:
            return value, offset
        shift += 7


def native_methods(data):
    assert data.startswith(b"dex\n"), "Expected a DEX file"
    string_count, strings_offset, type_count, types_offset, proto_count, protos_offset = unpack(data, "6I", 56)
    strings = []
    for index in range(string_count):
        offset = unpack(data, "I", strings_offset + index * 4)[0]
        _, offset = uleb(data, offset)
        strings.append(data[offset:data.index(b"\0", offset)].decode("utf-8", errors="replace"))
    types = [strings[unpack(data, "I", types_offset + index * 4)[0]] for index in range(type_count)]
    protos = []
    for index in range(proto_count):
        _, result, parameters = unpack(data, "III", protos_offset + index * 12)
        arguments = ""
        if parameters:
            count = unpack(data, "I", parameters)[0]
            arguments = "".join(types[unpack(data, "H", parameters + 4 + part * 2)[0]] for part in range(count))
        protos.append("(" + arguments + ")" + types[result])
    method_count, methods_offset, class_count, classes_offset = unpack(data, "4I", 88)
    methods = []
    for index in range(method_count):
        owner, proto, name = unpack(data, "HHI", methods_offset + index * 8)
        methods.append((types[owner][1:-1], strings[name], protos[proto]))
    result = set()
    for index in range(class_count):
        offset = unpack(data, "I", classes_offset + index * 32 + 24)[0]
        if not offset:
            continue
        counts = []
        for _ in range(4):
            count, offset = uleb(data, offset)
            counts.append(count)
        for _ in range(counts[0] + counts[1]):
            _, offset = uleb(data, offset)
            _, offset = uleb(data, offset)
        for count in counts[2:]:
            method_index = 0
            for _ in range(count):
                delta, offset = uleb(data, offset)
                flags, offset = uleb(data, offset)
                _, offset = uleb(data, offset)
                method_index += delta
                if flags & 0x100 and methods[method_index][0].startswith("com/unspektrawesome/"):
                    result.add(methods[method_index])
    return result


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--apk", type=Path, required=True)
    parser.add_argument("--identity", type=Path, required=True)
    parser.add_argument("--source", type=Path, required=True)
    parser.add_argument("--qa", action="store_true")
    args = parser.parse_args()
    identity = args.identity.read_text()
    package = re.search(r'packageName\[\] = "([^"]+)"', identity)[1]
    certificate = bytes(int(value, 16) for value in re.findall(r"0x([0-9a-f]{2})", identity))
    assert len(certificate) == 32
    if package == "com.unspektrawesome" and "debuggable = false" in identity:
        assert certificate.hex() == "8892d111163acf9c61c06b2b0e5a907b691d88f31d84c81e3d10dac0f1a6a79a"
    bindings = set()
    for source in args.source.rglob("*.cpp"):
        for name, signature, symbol in re.findall(
                r'\{"(\w+)", "([^"]+)", reinterpret_cast<void\*>\((Java_com_\w+)\)\}', source.read_text(encoding="utf-8")):
            owner, method = symbol[5:].rsplit("_", 1)
            assert method == name
            bindings.add((owner.replace("_", "/"), name, signature))
    assert bindings, "Native registrations are missing"
    packaged_methods = set()
    with zipfile.ZipFile(args.apk) as apk:
        libraries = ("libunspektrawesome_vulkan.so", "libunspektra_archive.so")
        for library in libraries:
            check_library(apk.read("lib/arm64-v8a/" + library), package, certificate)
        fixtures = {Path(name).name for name in apk.namelist() if "libunspektra_rejected_" in name}
        assert fixtures == ({"libunspektra_rejected_package.so", "libunspektra_rejected_certificate.so"} if args.qa else set()), "Incorrect QA fixture packaging"
        for name in apk.namelist():
            if re.fullmatch(r"classes\d*\.dex", name):
                packaged_methods.update(native_methods(apk.read(name)))
    assert bindings == packaged_methods, f"Native binding mismatch: missing={bindings - packaged_methods}, extra={packaged_methods - bindings}"
    print(f"Verified {len(libraries)} protected libraries and {len(bindings)} native bindings in {args.apk.name}")


if __name__ == "__main__":
    main()
