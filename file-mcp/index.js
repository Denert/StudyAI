#!/usr/bin/env node

import { Server } from "@modelcontextprotocol/sdk/server/index.js";
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
import { CallToolRequestSchema, ListToolsRequestSchema } from "@modelcontextprotocol/sdk/types.js";
import { readFileSync, writeFileSync, readdirSync, existsSync, mkdirSync } from "fs";
import { join } from "path";
import { homedir } from "os";

const FILES_DIR = join(homedir(), ".studyai", "files");

// Ensure directory exists
if (!existsSync(FILES_DIR)) {
  mkdirSync(FILES_DIR, { recursive: true });
}

const server = new Server(
  { name: "file-mcp", version: "1.0.0" },
  { capabilities: { tools: {} } }
);

server.setRequestHandler(ListToolsRequestSchema, async () => ({
  tools: [
    {
      name: "save_file",
      description: "Save content to a file in the StudyAI files directory (~/.studyai/files/)",
      inputSchema: {
        type: "object",
        properties: {
          filename: { type: "string", description: "Filename (e.g., 'notes.txt', 'summary.md')" },
          content: { type: "string", description: "Content to save" }
        },
        required: ["filename", "content"]
      }
    },
    {
      name: "read_file",
      description: "Read content from a file in the StudyAI files directory",
      inputSchema: {
        type: "object",
        properties: {
          filename: { type: "string", description: "Filename to read" }
        },
        required: ["filename"]
      }
    },
    {
      name: "list_files",
      description: "List all files in the StudyAI files directory",
      inputSchema: {
        type: "object",
        properties: {}
      }
    }
  ]
}));

server.setRequestHandler(CallToolRequestSchema, async (request) => {
  const { name, arguments: args } = request.params;

  try {
    if (name === "save_file") {
      const { filename, content } = args;

      if (!filename || !content) {
        return { content: [{ type: "text", text: "Error: filename and content are required" }], isError: true };
      }

      // Sanitize filename
      const safeName = filename.replace(/[^a-zA-Z0-9._-]/g, "_");
      const filePath = join(FILES_DIR, safeName);

      writeFileSync(filePath, content, "utf-8");

      return {
        content: [{
          type: "text",
          text: `File saved successfully!\n\nPath: ${filePath}\nSize: ${content.length} characters`
        }]
      };
    }

    if (name === "read_file") {
      const { filename } = args;

      if (!filename) {
        return { content: [{ type: "text", text: "Error: filename is required" }], isError: true };
      }

      const safeName = filename.replace(/[^a-zA-Z0-9._-]/g, "_");
      const filePath = join(FILES_DIR, safeName);

      if (!existsSync(filePath)) {
        return { content: [{ type: "text", text: `File not found: ${safeName}` }], isError: true };
      }

      const content = readFileSync(filePath, "utf-8");

      return {
        content: [{
          type: "text",
          text: `## File: ${safeName}\n\n${content}`
        }]
      };
    }

    if (name === "list_files") {
      const files = readdirSync(FILES_DIR);

      if (files.length === 0) {
        return { content: [{ type: "text", text: "No files found in ~/.studyai/files/" }] };
      }

      const list = files.map(f => `- ${f}`).join("\n");

      return {
        content: [{
          type: "text",
          text: `## Files in ~/.studyai/files/\n\n${list}\n\nTotal: ${files.length} files`
        }]
      };
    }

    return { content: [{ type: "text", text: `Unknown tool: ${name}` }], isError: true };
  } catch (error) {
    return { content: [{ type: "text", text: `Error: ${error.message}` }], isError: true };
  }
});

const transport = new StdioServerTransport();
await server.connect(transport);
console.error("File MCP Server running");
