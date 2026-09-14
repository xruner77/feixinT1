import verify_block

orig = verify_block.file_content
lines = orig.splitlines(keepends=True)

# lines 0 and 1 total length
orig_prefix = lines[0] + lines[1]
target_len = len(orig_prefix)
print("Original prefix length:", target_len)

# New prefix with sendto and recvfrom
prefix_parts = [
    b"sendto: 1\n",
    b"recvfrom: 1\n",
    b"sendmsg: 1\n",
    b"recvmsg: 1\n"
]
curr_len = sum(len(p) for p in prefix_parts)
remaining = target_len - curr_len
comment_line = b"# " + b"-" * (remaining - 3) + b"\n"
prefix_parts.append(comment_line)

new_prefix = b"".join(prefix_parts)
print("New prefix length:", len(new_prefix))
assert len(new_prefix) == target_len

new_file_content = new_prefix + b"".join(lines[2:])
print("New file content length:", len(new_file_content), "Original:", len(orig))
assert len(new_file_content) == len(orig)

# Build full 4096-byte block
new_block = new_file_content + verify_block.data[len(orig):]
print("New block length:", len(new_block))
assert len(new_block) == 4096

with open("d:\\tools\\adb\\block_327225_patched.bin", "wb") as f:
    f.write(new_block)

print("Saved d:\\tools\\adb\\block_327225_patched.bin successfully!")
