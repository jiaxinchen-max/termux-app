#!/usr/bin/env ruby
# frozen_string_literal: true

require "yaml"
require "date"

def fail_with(message)
  warn "FAIL: #{message}"
  exit 1
end

artifact_path = ARGV[0] || fail_with("usage: validate-yaml-schema.rb <artifact> [schema]")
artifact = YAML.safe_load(File.read(artifact_path), permitted_classes: [Date, Time], aliases: true)
fail_with("#{artifact_path} must contain a YAML map") unless artifact.is_a?(Hash)

schema_path = ARGV[1]
unless schema_path
  schema_path = {
    "run_state_v1" => ".harness/schemas/run_state.schema.yaml",
    "change_manifest_v1" => ".harness/schemas/change_manifest.schema.yaml",
    "evidence_manifest_v1" => ".harness/schemas/evidence_manifest.schema.yaml",
    "review_findings_v1" => ".harness/schemas/review_findings.schema.yaml"
  }[artifact["schema"]]
end
fail_with("unknown schema #{artifact['schema'].inspect} for #{artifact_path}") unless schema_path

definition = YAML.safe_load(File.read(schema_path), permitted_classes: [Date, Time], aliases: true)
Array(definition["required"]).each do |field|
  fail_with("#{artifact_path}: missing #{field}") unless artifact.key?(field)
end

def collect_values(value, segments)
  return [value] if segments.empty?

  segment = segments.first
  rest = segments.drop(1)
  case value
  when Array
    value.flat_map { |item| collect_values(item, segments) }
  when Hash
    if segment == "value"
      value.values
    elsif value.key?(segment)
      collect_values(value[segment], rest)
    else
      []
    end
  else
    []
  end
end

(definition["enums"] || {}).each do |path, allowed|
  collect_values(artifact, path.split(".")).each do |value|
    fail_with("#{artifact_path}: #{path}=#{value.inspect} is invalid") unless allowed.include?(value)
  end
end

puts "OK: #{artifact_path}"
