export type EnvironmentName = "dev" | "test" | "production";

export interface ApiEnvironment {
  readonly name: EnvironmentName;
  readonly apiBaseUrl: string;
}

const ENVIRONMENTS: Record<EnvironmentName, ApiEnvironment> = {
  dev: {
    name: "dev",
    apiBaseUrl: "http://127.0.0.1:8080",
  },
  test: {
    name: "test",
    apiBaseUrl: "http://127.0.0.1:8080",
  },
  production: {
    name: "production",
    apiBaseUrl: "",
  },
};

export function resolveEnvironment(name: EnvironmentName = "dev"): ApiEnvironment {
  return ENVIRONMENTS[name];
}
