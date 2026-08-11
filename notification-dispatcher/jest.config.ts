import type { Config } from 'jest';

const config: Config = {
  preset: 'ts-jest',
  testEnvironment: 'node',
  roots: ['<rootDir>/src'],
  testMatch: ['**/__tests__/**/*.test.ts'],
  collectCoverageFrom: ['src/**/*.ts', '!src/index.ts', '!src/logger.ts'],
  coverageThreshold: {
    global: { lines: 80, functions: 80, branches: 70 }
  },
  verbose: true,
  moduleNameMapper: {
    // Strip .js from imports — ts-jest resolves to .ts automatically
    '^(\\.{1,2}/.*)\\.js$': '$1',
  },
};

export default config;