#!/bin/sh
set -eu

output_dir="${1:-build/contracts}"
version="${2:-dev}"
package_name="${CONTRACTS_PACKAGE_NAME:-registry-contracts}"

mkdir -p "$output_dir/$package_name/contracts"
cp -R contracts/kafka "$output_dir/$package_name/contracts/kafka"

tarball_path="$output_dir/$package_name.tar.gz"
tar -C "$output_dir" -czf "$tarball_path" "$package_name"

echo "Packaged $package_name version $version at $tarball_path"
