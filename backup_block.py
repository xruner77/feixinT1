import verify_block

# Save backup of the original block
original_block = verify_block.data
with open("d:\\tools\\adb\\block_327225_backup.bin", "wb") as f:
    f.write(original_block)

print(f"Saved original block backup ({len(original_block)} bytes) to d:\\tools\\adb\\block_327225_backup.bin")
