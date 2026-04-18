import type { Metadata, Viewport } from "next";
import { Inter, JetBrains_Mono } from "next/font/google";

import "./globals.css";

const inter = Inter({
    variable: "--font-inter",
    subsets: ["latin"],
    display: "swap",
});

const jetbrainsMono = JetBrains_Mono({
    variable: "--font-jetbrains-mono",
    subsets: ["latin"],
    display: "swap",
});

export const metadata: Metadata = {
    title: "RubberDuck Live — Wellness Dashboard",
    description:
        "Real-time wellness dashboard for developers. Watches your IDE, surfaces stuck moments, and nudges you with Socratic questions.",
    icons: { icon: "/favicon.ico" },
};

export const viewport: Viewport = {
    themeColor: "#0b0f17",
    colorScheme: "dark",
};

export default function RootLayout({ children }: { children: React.ReactNode }) {
    return (
        <html lang="en" className="dark">
            <body
                className={`${inter.variable} ${jetbrainsMono.variable} min-h-screen bg-background font-sans text-foreground antialiased`}
            >
                {children}
            </body>
        </html>
    );
}
