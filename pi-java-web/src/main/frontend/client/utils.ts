/** 截断文本到 maxLen 个字符，超出加省略号（换行折叠为空格）。 */
export function truncateText(text: string, maxLen: number): string {
  if (!text) return "";
  const cleaned = text.replace(/\n/g, " ").trim();
  return cleaned.length > maxLen ? cleaned.slice(0, maxLen) + "..." : cleaned;
}
