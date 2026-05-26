import fs from "node:fs/promises";
import path from "node:path";
import vm from "node:vm";

const workspaceRoot = process.cwd();
const frontendIndexPath = path.join(
  workspaceRoot,
  "zodiac-dewey-frontend",
  "frontend",
  "assets",
  "scripts",
  "pages",
  "index.js"
);
const sinomapDir = path.join(workspaceRoot, "node_modules", "sinomap", "resources");
const outputPath = path.join(
  workspaceRoot,
  "zodiac-dewey-backend",
  "src",
  "main",
  "resources",
  "birth-place-coordinates.json"
);

const PROVINCE_RESOURCE_MAP = {
  "北京市": "beijing",
  "天津市": "tianjin",
  "河北省": "hebei",
  "山西省": "shan1xi",
  "内蒙古自治区": "neimenggu",
  "辽宁省": "liaoning",
  "吉林省": "jilin",
  "黑龙江省": "heilongjiang",
  "上海市": "shanghai",
  "江苏省": "jiangsu",
  "浙江省": "zhejiang",
  "安徽省": "anhui",
  "福建省": "fujian",
  "江西省": "jiangxi",
  "山东省": "shandong",
  "河南省": "henan",
  "湖北省": "hubei",
  "湖南省": "hunan",
  "广东省": "guangdong",
  "广西壮族自治区": "guangxi",
  "海南省": "hainan",
  "重庆市": "chongqing",
  "四川省": "sichuan",
  "贵州省": "guizhou",
  "云南省": "yunnan",
  "西藏自治区": "xizang",
  "陕西省": "shan3xi",
  "甘肃省": "gansu",
  "青海省": "qinghai",
  "宁夏回族自治区": "ningxia",
  "新疆维吾尔自治区": "xinjiang",
  "台湾省": "taiwan",
  "香港特别行政区": "hongkong",
  "澳门特别行政区": "macau"
};

function normalizeName(value) {
  return String(value || "")
    .trim()
    .replace(/\s+/g, "")
    .replace(
      /(壮族|回族|维吾尔|蒙古族|朝鲜族|哈尼族|彝族|傣族|黎族|藏族|苗族|土家族|哈萨克|柯尔克孜|侗族|布依族|满族|瑶族|白族|傈僳族|景颇族|羌族|锡伯|东乡族|仫佬族|仡佬族|水族|纳西族|土族|达斡尔|仡佬|撒拉族|毛南族|仫佬|佤族|拉祜族|畲族|高山族|鄂温克|鄂伦春|独龙族|赫哲族|门巴族|珞巴族)/g,
      ""
    )
    .replace(
      /(特别行政区|自治区|自治州|自治县|自治旗|矿区|林区|新区|开发区|管理区|风景名胜区|高新技术产业开发区|经济技术开发区|经济开发区|工业园区|示范区|合作区|功能区|试验区)$/g,
      ""
    )
    .replace(/[省市区县旗盟]$/g, "");
}

function averageCoordinates(list) {
  if (!list.length) return null;
  const sum = list.reduce(
    (acc, item) => {
      acc.lng += item.lng;
      acc.lat += item.lat;
      return acc;
    },
    { lng: 0, lat: 0 }
  );
  return {
    lng: Number((sum.lng / list.length).toFixed(6)),
    lat: Number((sum.lat / list.length).toFixed(6))
  };
}

function extractCityData(sourceText) {
  const marker = "const CITY_DATA = ";
  const markerIndex = sourceText.indexOf(marker);
  if (markerIndex < 0) {
    throw new Error("CITY_DATA not found in frontend index.js");
  }
  const braceStart = sourceText.indexOf("{", markerIndex);
  let depth = 0;
  let endIndex = braceStart;
  for (; endIndex < sourceText.length; endIndex += 1) {
    const char = sourceText[endIndex];
    if (char === "{") depth += 1;
    if (char === "}") {
      depth -= 1;
      if (depth === 0) {
        endIndex += 1;
        break;
      }
    }
  }
  const literal = sourceText.slice(braceStart, endIndex);
  return vm.runInNewContext(`(${literal})`);
}

function flattenFeatureCoordinates(featureCollection) {
  const exactMap = new Map();
  const normalizedMap = new Map();
  for (const feature of featureCollection.features || []) {
    const name = feature?.properties?.name;
    const cp = feature?.properties?.cp;
    if (!name || !Array.isArray(cp) || cp.length < 2) continue;
    const entry = {
      lng: Number(cp[0]),
      lat: Number(cp[1]),
      sourceName: name
    };
    exactMap.set(name, entry);
    const normalized = normalizeName(name);
    if (normalized && !normalizedMap.has(normalized)) {
      normalizedMap.set(normalized, entry);
    }
  }
  return { exactMap, normalizedMap };
}

async function main() {
  const sourceText = await fs.readFile(frontendIndexPath, "utf8");
  const cityData = extractCityData(sourceText);
  const flattenedLookup = {};
  const unresolved = [];

  for (const [province, cities] of Object.entries(cityData)) {
    const resourceBase = PROVINCE_RESOURCE_MAP[province];
    if (!resourceBase) {
      unresolved.push({ province, city: "", district: "", reason: "missing_resource_mapping" });
      continue;
    }
    const provinceJson = JSON.parse(
      await fs.readFile(path.join(sinomapDir, `${resourceBase}.json`), "utf8")
    );
    const { exactMap, normalizedMap } = flattenFeatureCoordinates(provinceJson);
    const provinceCoords = [...exactMap.values()];
    const provinceCenter = averageCoordinates(provinceCoords);

    for (const [city, districts] of Object.entries(cities)) {
      const cityMatches = [];
      const cityResolved = new Map();

      for (const district of districts) {
        const exact = exactMap.get(district);
        const normalized = normalizedMap.get(normalizeName(district));
        const coords = exact || normalized || null;
        if (coords) {
          cityMatches.push(coords);
          cityResolved.set(district, {
            lng: coords.lng,
            lat: coords.lat,
            source: exact ? "exact" : "normalized",
            sourceName: coords.sourceName
          });
        }
      }

      const cityCenter = averageCoordinates(cityMatches) || provinceCenter;
      for (const district of districts) {
        const placeKey = `${province} ${city} ${district}`;
        const resolved = cityResolved.get(district);
        if (resolved) {
          flattenedLookup[placeKey] = {
            lng: resolved.lng,
            lat: resolved.lat
          };
          continue;
        }
        if (cityCenter) {
          flattenedLookup[placeKey] = {
            lng: cityCenter.lng,
            lat: cityCenter.lat
          };
          unresolved.push({ province, city, district, reason: "fallback_city_or_province_center" });
        } else {
          unresolved.push({ province, city, district, reason: "no_coordinates_found" });
        }
      }
    }
  }

  await fs.mkdir(path.dirname(outputPath), { recursive: true });
  await fs.writeFile(outputPath, JSON.stringify(flattenedLookup, null, 2) + "\n", "utf8");

  const fallbackCount = unresolved.filter((item) => item.reason === "fallback_city_or_province_center").length;
  const hardMissingCount = unresolved.filter((item) => item.reason === "no_coordinates_found").length;
  console.log(
    JSON.stringify(
      {
        outputPath,
        total: Object.keys(flattenedLookup).length,
        fallbackCount,
        hardMissingCount,
        sampleUnresolved: unresolved.slice(0, 20)
      },
      null,
      2
    )
  );
}

main().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});
