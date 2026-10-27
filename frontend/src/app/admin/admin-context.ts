"use client";

import { createContext, useContext } from "react";

export const AdminContext = createContext<{ email: string } | null>(null);

/** Email of the signed-in admin; only valid under the (console) gate. */
export function useAdmin() {
  const admin = useContext(AdminContext);
  if (!admin) throw new Error("useAdmin must be used inside the admin console");
  return admin;
}
