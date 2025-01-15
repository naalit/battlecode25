import sys
import os
import shutil
import re
from pathlib import Path

def find_package_root(directory: str, file_path: str) -> str:
    """
    Find the root directory of the Java package structure by looking for the first
    directory containing a .java file.
    """
    current_path = os.path.dirname(file_path)
    while current_path.startswith(directory):
        parent_path = os.path.dirname(current_path)
        if parent_path == current_path:  # Reached root
            break
        # Check if parent contains any .java files
        if not any(f.endswith('.java') for f in os.listdir(parent_path)):
            return parent_path
        current_path = parent_path
    return directory

def get_new_package_name(root_dir: str, file_path: str) -> str:
    """
    Determine the package name based on the file's position relative to the package root.
    """
    # Convert paths to absolute paths
    root_dir = os.path.abspath(root_dir)
    file_path = os.path.abspath(file_path)

    # Find the actual package root
    package_root = find_package_root(root_dir, file_path)

    # Get the relative path from the package root
    relative_path = os.path.dirname(os.path.relpath(file_path, package_root))

    # Convert path separators to dots and remove leading/trailing dots
    return relative_path.replace(os.sep, '.').strip('.')

def update_java_file(src_file: str, dest_file: str, src_root: str, dest_root: str):
    """
    Update package declarations and imports in the Java file.
    """
    old_package = get_new_package_name(src_root, src_file)
    new_package = get_new_package_name(dest_root, dest_file)

    with open(src_file, 'r', encoding='utf-8') as f:
        content = f.read()

    # Update package declaration
    if old_package:
        package_pattern = r'package\s+' + re.escape(old_package) + r'\s*;'
        if re.search(package_pattern, content):
            content = re.sub(package_pattern, f'package {new_package};', content)
        else:
            # If no package declaration found, add it at the start of the file
            content = f'package {new_package};\n\n{content}'

    # Update imports that reference the old package
    if old_package:
        content = re.sub(
            r'import\s+' + re.escape(old_package) + r'\.([^;]+)\s*;',
            f'import {new_package}.\\1;',
            content
        )
        content = re.sub(
            r'import static\s+' + re.escape(old_package) + r'\.([^;]+)\s*;',
            f'import static {new_package}.\\1;',
            content
        )

    # Create destination directory if it doesn't exist
    os.makedirs(os.path.dirname(dest_file), exist_ok=True)

    # Write updated content to destination file
    with open(dest_file, 'w', encoding='utf-8') as f:
        f.write(content)

def migrate_java_files(source_dir: str, dest_dir: str):
    """
    Find and migrate all Java files from source to destination directory.
    """
    # Convert to absolute paths
    source_dir = os.path.abspath(source_dir)
    dest_dir = os.path.abspath(dest_dir)

    # Verify directories exist
    if not os.path.exists(source_dir):
        raise ValueError(f"Source directory does not exist: {source_dir}")

    # Create destination directory if it doesn't exist
    os.makedirs(dest_dir, exist_ok=True)

    # Find all Java files in source directory
    java_files_found = False
    for root, _, files in os.walk(source_dir):
        for file in files:
            if file.endswith('.java'):
                java_files_found = True
                src_file = os.path.join(root, file)

                # Create corresponding destination path
                rel_path = os.path.relpath(src_file, source_dir)
                dest_file = os.path.join(dest_dir, rel_path)

                print(f"Processing: {rel_path}")
                print(f"  Source package: {get_new_package_name(source_dir, src_file)}")
                print(f"  Destination package: {get_new_package_name(dest_dir, dest_file)}")
                update_java_file(src_file, dest_file, source_dir, dest_dir)

    if not java_files_found:
        print("Warning: No Java files found in source directory")

def main():
    if len(sys.argv) != 3:
        print("Usage: python script.py <source_directory> <destination_directory>")
        sys.exit(1)

    source_dir = sys.argv[1]
    dest_dir = sys.argv[2]

    try:
        migrate_java_files(source_dir, dest_dir)
        print("Migration completed successfully!")
    except Exception as e:
        print(f"Error during migration: {e}")
        sys.exit(1)

if __name__ == "__main__":
    main()