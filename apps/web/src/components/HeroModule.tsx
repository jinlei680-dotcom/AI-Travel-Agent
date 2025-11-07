import type { ReactNode } from "react";

export default function HeroModule({ children }: { children?: ReactNode }) {
  return (
    <section
      className="relative min-h-screen"
      style={{
        backgroundImage:
          "url(https://picsum.photos/id/1018/1920/1080)",
        backgroundSize: "cover",
        backgroundPosition: "center",
      }}
    >
      {/* 遮罩以保证文字可读性 */}
      <div className="absolute inset-0 bg-gradient-to-r from-black/60 via-black/40 to-black/20" />
      {/* 内容 */}
      <div className="relative z-10 container-narrow h-full flex flex-col items-center justify-center text-center">
        <div className="inline-flex items-center rounded-full border border-white/20 px-3 py-1 text-xs text-white/80 mb-4">
          简洁 · 实用
        </div>
        <h1 className="text-5xl sm:text-6xl md:text-7xl font-extrabold tracking-tight text-white">
          AI 旅行规划助手
        </h1>
        <p className="mt-4 sm:mt-6 text-lg sm:text-2xl text-white/90 max-w-3xl">
          一句话生成每日行程，含地图导航与线路优化
        </p>
        {children && (
          <div className="mt-[22vh] sm:mt-[24vh] lg:mt-[26vh] w-full max-w-3xl text-left">
            {children}
          </div>
        )}
      </div>
      {/* 下滑指示 */}
      <div className="absolute bottom-6 left-0 right-0 mx-auto w-6 h-10 rounded-full border border-white/40 flex items-start justify-center">
        <span className="mt-1 h-2 w-1 rounded-full bg-white/70 animate-bounce" />
      </div>
    </section>
  );
}